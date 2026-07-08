# Does Jackson Serialization-Free Load Configured ObjectMapper Customizers?

## TL;DR Answer

**YES - As of PR #55216**, the reflection-free Jackson serializers **now respect ObjectMapper customizers for field visibility settings**.

However, this is **LIMITED** to visibility configurations. Other customizer behaviors may not be fully supported.

---

## What Changed in PR #55216

The PR titled "Avoid duplicate fields when using @JsonProperty + improve Kotlin support + obey visibility defined in ObjectMapperCustomizer in reflection-free Jackson serializers" made three key improvements:

### 1. Fixed Duplicate Fields with @JsonProperty ✅
- **Problem**: Using `@JsonProperty("name")` on `val field: String` produced `{"name": "value", "field": "value"}`
- **Fix**: The serializer now tracks which methods are bound to fields and skips duplicate getter methods
- **Code**: `JacksonSerializerFactory.collectAllFieldSpecs()` now maintains a `boundMethods` set

### 2. Improved Kotlin Support ✅
- **Problem**: Kotlin properties were being treated as JavaBeans (applying "is" prefix stripping)
- **Fix**: Detects Kotlin classes via `kotlin.Metadata` annotation and uses proper accessor methods
- **Code**: `JacksonCodeGenerator.getterMethodInfo()` checks for `KOTLIN_METADATA` annotation

### 3. **Respects ObjectMapperCustomizer Visibility Settings** ✅
- **This answers your question!**
- **Problem**: Auto-detected getters were always serialized, ignoring visibility customizers
- **Fix**: At **runtime**, queries the `SerializerProvider` for visibility settings and conditionally serializes getters

---

## How Visibility Customizers Are Loaded

### The Implementation

**New Runtime Methods** (in `JacksonMapperUtil.java`):
```java
public static boolean isPublicGetterVisible(SerializerProvider provider) {
    return provider.getConfig().getDefaultVisibilityChecker()
            .isGetterVisible(VISIBILITY_TEST_METHOD);
}

public static boolean isPublicIsGetterVisible(SerializerProvider provider) {
    return provider.getConfig().getDefaultVisibilityChecker()
            .isIsGetterVisible(VISIBILITY_TEST_METHOD);
}
```

**Generated Serializer Code** (in `JacksonSerializerFactory.java`):
```java
ResultHandle getterVisibleHandle = null;
if (hasAutoDetectedGetters) {
    getterVisibleHandle = bytecode.invokeStaticMethod(
        MethodDescriptor.ofMethod(JacksonMapperUtil.class, "isPublicGetterVisible",
            boolean.class, SerializerProvider.class),
        ctx.serializerProvider);
}

// For each field that's an auto-detected getter:
if (fieldSpecs.isAutoDetectedGetter()) {
    ResultHandle visibleHandle = fieldSpecs.methodInfo.name().startsWith("is")
        ? isGetterVisibleHandle
        : getterVisibleHandle;
    writeBranch = writeBranch.ifTrue(visibleHandle).trueBranch();
}
```

### How It Works

1. **Build Time**: The serializer generator detects which fields are "auto-detected getters" (getters without explicit `@JsonProperty` or `@JsonGetter`)

2. **Runtime**: For these auto-detected getters, the generated serializer:
   - Receives the `SerializerProvider` as a parameter (method signature: `serializeContent(Object object, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)`)
   - Calls `JacksonMapperUtil.isPublicGetterVisible(serializerProvider)` 
   - The `SerializerProvider` contains the **configured ObjectMapper** with all customizers applied
   - Only serializes the getter if visibility check returns `true`

3. **ObjectMapper Customizers** are applied when the ObjectMapper is created at application startup, so the `SerializerProvider.getConfig()` includes all customizations

### Test Case Example

From `FieldVisibilityReflectionFreeTest.java`:

```java
@Singleton
public static class FieldOnlyVisibilityCustomizer implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper mapper) {
        mapper.setVisibility(
            mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)  // ← Hides getters
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE) // ← Hides is-getters
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
    }
}

public static class Item {
    private String secret;
    
    public String getExposed() {  // ← This getter is NOT serialized
        return secret;
    }
    
    public String getSecret() {   // ← This getter is NOT serialized
        return secret;
    }
}
```

**Result**: JSON only contains `{"secret": "hidden"}` from the field, not `{"exposed": "...", "secret": "..."}` from the getters.

---

## What ObjectMapperCustomizer Settings ARE Supported?

Based on the fix, the following are **explicitly supported**:

### ✅ Supported (Via Runtime Checks)
- **Field visibility** (`withFieldVisibility`)
- **Getter visibility** (`withGetterVisibility`)  
- **Is-getter visibility** (`withIsGetterVisibility`)
- **PropertyNamingStrategy** (already supported before PR #55216)
- **JsonInclude settings** (already supported before PR #55216)
- **Active View** (already supported before PR #55216)
- **Security/Roles** (already supported before PR #55216)

### ❓ Possibly NOT Supported (Build-Time Limitation)
The reflection-free serializers are **generated at build time**, so customizers that run at **runtime** may not fully affect them:

- **Custom JsonSerializer/JsonDeserializer registrations** - May not be used by reflection-free code
- **Custom AnnotationIntrospector** (like `KotlinNamesAnnotationIntrospector`) - Not fully integrated (see Kotlin issues)
- **Jackson Modules** - Only partially integrated (see analysis below)
- **Custom type mappings** - May fall back to standard Jackson for complex types

---

## Key Limitation: Jackson Modules

As documented in your `FINDINGS.md`, there's a **fundamental timing problem**:

### The Problem
1. **Reflection-free serializers** are generated at **BUILD TIME**
2. **Jackson Modules** (like `KotlinModule`) are registered at **RUNTIME** via `ObjectMapperCustomizer`
3. The generated serializers use hardcoded JavaBean conventions and cannot adapt to module-specific behavior

### What This Means
- The `SerializerProvider` passed at runtime **does have** the registered modules
- But the **generated bytecode** was already created without knowledge of those modules
- So module-specific behaviors like:
  - `KotlinModule`'s property naming (no "is" stripping for Kotlin)
  - Custom `AnnotationIntrospector` implementations
  - Module-specific serialization logic
  
  Are **not fully applied** to reflection-free serializers.

### PR #55216's Workaround for Kotlin
The PR adds **Kotlin-specific detection** at build time:
```java
private static final DotName KOTLIN_METADATA = DotName.createSimple("kotlin.Metadata");

// In getterMethodInfo():
if (classInfo.hasDeclaredAnnotation(KOTLIN_METADATA)) {
    return namedAccessor;  // Use property name as-is, don't apply JavaBean rules
}
```

This is a **build-time workaround** that partially emulates what `KotlinModule` would do at runtime.

---

## Summary Table

| ObjectMapperCustomizer Setting | Supported? | How? |
|-------------------------------|------------|------|
| Visibility (fields, getters, is-getters) | ✅ YES | Runtime check via SerializerProvider |
| PropertyNamingStrategy | ✅ YES | Runtime query via SerializerProvider |
| JsonInclude settings | ✅ YES | Runtime check via SerializerProvider |
| Active View | ✅ YES | Runtime check via SerializerProvider |
| Security/Roles | ✅ YES | Runtime check via SerializerProvider |
| Jackson Modules (general) | ⚠️ PARTIAL | Build-time only, not fully integrated |
| KotlinModule (specifically) | ⚠️ PARTIAL | Build-time workaround for Kotlin classes |
| Custom Serializers/Deserializers | ❓ UNKNOWN | May fall back to standard Jackson |
| Custom AnnotationIntrospector | ❌ NO | Not used at build time |

---

## Conclusion

**The reflection-free Jackson serializers DO load and respect ObjectMapperCustomizer configurations for visibility settings** as of PR #55216. The `SerializerProvider` parameter passed to the generated serializers contains the fully configured ObjectMapper with all customizers applied.

However, this support is **limited to runtime-checkable settings** like visibility. Module-specific behaviors that require build-time code generation awareness (like Kotlin's property naming) require special build-time handling, which PR #55216 partially addresses for Kotlin classes.

The architecture remains:
- **Build time**: Generate serializer bytecode with JavaBean conventions (+ Kotlin-specific tweaks)
- **Runtime**: Query SerializerProvider for configuration and conditionally execute generated code

This hybrid approach works well for visibility and include settings but has limitations for more complex module-based customizations.
