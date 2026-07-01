# Findings: Root Cause Analysis

## Executive Summary

The Quarkus reflection-free Jackson serializer generates bytecode at build-time using **JavaBean conventions**, but it has **no awareness of Kotlin** or **jackson-module-kotlin**. 

**Critical Issue**: According to [Quarkus Kotlin documentation](https://quarkus.io/guides/kotlin#kotlin-jackson), Quarkus **automatically registers KotlinModule** to the ObjectMapper when `jackson-module-kotlin` is on the classpath. However, this happens at **runtime**, while reflection-free serializers are generated at **build time** - creating a fundamental incompatibility.

This causes two critical issues:

1. **Boolean "is" prefix**: Applies JavaBean rule "strip 'is'" to Kotlin properties, but `jackson-module-kotlin` keeps the actual Kotlin property name
2. **Duplicate fields**: Processes fields and getters separately, not recognizing that Kotlin properties generate both

## Issue #1: Boolean "is" Prefix Handling

### The Problem

**Without jackson-module-kotlin**: Jackson uses JavaBean conventions → `isEligible()` getter means property name is `"eligible"` (strips "is")

**With jackson-module-kotlin**: Uses Kotlin reflection → Actual Kotlin property name is `"isEligible"` (doesn't strip "is")

**Quarkus reflection-free serializer**: Always uses JavaBean conventions, causing a mismatch when jackson-module-kotlin is present.

### Why This Happens

**Kotlin Properties ≠ JavaBeans**

Kotlin has a **first-class property system**:
```kotlin
val isEligible: Boolean  // This IS the property name
```

This generates in bytecode:
- Field: `isEligible`
- Getter: `isEligible()` (matches property name exactly, NOT `getIsEligible()`)

You can verify this with:
```bash
javap -p TestWithIsDto.class
```

Output:
```
public final class TestWithIsDto {
  private final boolean isEligible;
  public final boolean isEligible();    // ← Note: NOT getIsEligible()
  public final String getName();
  // ...
}
```

**JavaBean Convention** (what standard Jackson uses):
- `isEligible()` method → assumes property is `"eligible"` (strips "is" prefix)
- This is a CONVENTION for deriving property names from getter methods

**jackson-module-kotlin knows better:**
- Uses `KotlinNamesAnnotationIntrospector`
- Reads Kotlin metadata to get the ACTUAL property name
- Sees that the Kotlin property is literally named `isEligible`
- Doesn't apply JavaBean "is" stripping because this isn't a JavaBean
- Result: property name = `"isEligible"`

**Result:**
- Standard Jackson serializes as `{"eligible": true}`
- jackson-module-kotlin serializes as `{"isEligible": true}`
- Quarkus reflection-free serializer uses JavaBean rules → generates `"eligible"`
- But when jackson-module-kotlin is present for deserialization → expects `"isEligible"`
- **Mismatch = Round-trip serialization failure!**

### Root Cause in Quarkus Code

**Location**: `extensions/resteasy-reactive/rest-jackson/deployment/src/main/java/io/quarkus/resteasy/reactive/jackson/deployment/processor/JacksonCodeGenerator.java`

**Method**: `FieldSpecs.fieldNameFromMethod()` (lines 583-595)

```java
private String fieldNameFromMethod(MethodInfo methodInfo) {
    String methodName = methodInfo.name();
    if (methodName.equals("get") || methodName.equals("set") || methodName.equals("is")) {
        return methodName;
    }
    if (methodName.startsWith("is")) {
        return methodName.substring(2, 3).toLowerCase() + methodName.substring(3);  // ← HARDCODED "is" STRIPPING
    }
    if (methodName.startsWith("get") || methodName.startsWith("set")) {
        return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
    }
    return methodName;
}
```

**Problem**:
1. Line 588-589: Hardcoded JavaBean "is" prefix stripping
2. No check if jackson-module-kotlin is registered with the ObjectMapper
3. No use of Kotlin reflection metadata
4. No awareness that Kotlin properties are not JavaBeans

**Flow**:
1. **Serialization (reflection-free)**: Uses `"eligible"` → JSON: `{"eligible": true}`
2. **Deserialization (jackson-module-kotlin)**: Expects `"isEligible"` → `UnrecognizedPropertyException`

## Issue #2: @JsonProperty Creates Duplicate Fields

### The Problem

When using `@JsonProperty` on a Kotlin property:
```kotlin
data class TestDto(
    @JsonProperty("name")
    val field: String
)
```

**Expected JSON**: `{"name": "value"}`

**Actual JSON**: `{"name": "value", "field": "value"}` ← Duplicate!

### Why This Happens

For a Kotlin property, the compiler generates BOTH:
- A field: `field`
- A getter method: `getField()`

The reflection-free serializer processes these **separately**, creating two FieldSpecs with different JSON names.

### Root Cause in Quarkus Code

**Location**: `extensions/resteasy-reactive/rest-jackson/deployment/src/main/java/io/quarkus/resteasy/reactive/jackson/deployment/processor/JacksonSerializerFactory.java`

**Method**: `collectAllFieldSpecs()` (lines 334-353)

```java
private List<FieldSpecs> collectAllFieldSpecs(ClassInfo classInfo, PropertyNamingStrategy namingStrategy) {
    List<FieldSpecs> allSpecs = new ArrayList<>();
    MethodInfo constructor = findConstructor(classInfo).orElse(null);

    // Collects from FIELDS
    for (FieldInfo fieldInfo : classFields(classInfo)) {
        FieldSpecs fieldSpecs = fieldSpecsFromField(classInfo, constructor, fieldInfo, namingStrategy);
        if (fieldSpecs != null) {
            allSpecs.add(fieldSpecs);  // ← Adds field with @JsonProperty("name")
        }
    }

    // Collects from METHODS (getters)
    for (MethodInfo methodInfo : classMethods(classInfo)) {
        FieldSpecs fieldSpecs = fieldSpecsFromMethod(methodInfo, namingStrategy);
        if (fieldSpecs != null) {
            allSpecs.add(fieldSpecs);  // ← Adds getter WITHOUT @JsonProperty
        }
    }

    return sortByPropertyOrder(classInfo, allSpecs);
}
```

**Flow**:
1. **First iteration (fields)**: Processes field `field` with `@JsonProperty("name")` → creates FieldSpecs with `jsonName = "name"`
2. **Second iteration (methods)**: Processes getter `getField()` without annotation → creates FieldSpecs with `jsonName = "field"`
3. Both are added to `allSpecs` list
4. Both get serialized because their `jsonName` values are different

**Duplicate Prevention Logic** (in `serializeObjectData()`, line 321):
```java
if (serializedFields.add(fieldSpecs.jsonName)) {
    // Only serialize if jsonName wasn't already added
}
```

This SHOULD prevent duplicates, but it doesn't work because:
- Field has `jsonName = "name"` (from @JsonProperty)
- Getter has `jsonName = "field"` (derived from method name)
- They're different, so both get serialized!

### Comparison: What Should Happen

**Method**: `fieldSpecsFromField()` in `JacksonCodeGenerator.java` (lines 449-462)

This method DOES handle field + getter together:
```java
protected FieldSpecs fieldSpecsFromField(ClassInfo classInfo, MethodInfo constructor, FieldInfo fieldInfo,
        PropertyNamingStrategy namingStrategy) {
    if (Modifier.isStatic(fieldInfo.flags())) {
        return null;
    }
    MethodInfo getterMethodInfo = getterMethodInfo(classInfo, fieldInfo);
    if (getterMethodInfo != null) {
        return new FieldSpecs(constructor, fieldInfo, getterMethodInfo, namingStrategy);  // ← Combines field + getter
    }
    if (Modifier.isPublic(fieldInfo.flags())) {
        return new FieldSpecs(fieldInfo, namingStrategy);
    }
    return null;
}
```

When creating a FieldSpecs with both field and method, it reads annotations from both:
```java
FieldSpecs(MethodInfo constructor, FieldInfo fieldInfo, MethodInfo methodInfo, PropertyNamingStrategy namingStrategy) {
    if (fieldInfo != null) {
        this.fieldInfo = fieldInfo;
        readAnnotations(fieldInfo);  // ← Reads @JsonProperty from field
    }
    if (methodInfo != null) {
        this.methodInfo = methodInfo;
        readAnnotations(methodInfo);  // ← Merges annotations
    }
    // ...
}
```

**But** the serializer's `collectAllFieldSpecs()` doesn't use this approach - it processes fields and methods separately.

## Why Reflection-Based Serializers Work

The standard Jackson reflection-based serializers:
1. **Use jackson-module-kotlin** which understands Kotlin property conventions
2. Have proper introspection that avoids processing the same property twice
3. Respect annotation inheritance from fields to their getters
4. Don't generate code at build time, so they can use runtime ObjectMapper configuration

## Fix Strategies

### Issue #1 Fix Options:

**Option A: Detect jackson-module-kotlin at build time**
1. Check if `jackson-module-kotlin` is on the classpath
2. If present AND class is a Kotlin class, don't strip "is" prefix
3. Use Kotlin reflection metadata if available

**Option B: Query the ObjectMapper at build time**
1. Check if KotlinModule is registered with the ObjectMapper
2. Use the same property naming logic as jackson-module-kotlin

**Option C: Disable reflection-free for Kotlin classes**
1. When jackson-module-kotlin is present, don't generate reflection-free serializers for Kotlin classes
2. Fall back to standard Jackson serialization

### Issue #2 Fix Options:

**Option A: Track processed properties**
1. When processing methods, check if a corresponding field was already processed
2. Skip getter methods that match already-processed fields
3. Compare based on the derived property name, not the jsonName

**Option B: Change collection logic**
1. Only collect from fields (which already combines field + getter)
2. Only process methods that don't have corresponding fields
3. This matches what `fieldSpecsFromField()` already does

**Option C: Deduplicate after collection**
1. After collecting all FieldSpecs, detect duplicates
2. When field and getter exist for same property, keep only the one with annotations
3. Priority: explicit @JsonProperty > field > method

## Files to Modify

### For Issue #1:
**File**: `JacksonCodeGenerator.java`
- **Method**: `fieldNameFromMethod()` (line 583-595)
- **Fix**: Add Kotlin-awareness before applying "is" stripping

### For Issue #2:
**File**: `JacksonSerializerFactory.java`
- **Method**: `collectAllFieldSpecs()` (line 334-353)
- **Fix**: Prevent duplicate field/getter collection

### Possible New Classes:
- `KotlinSerializationDetector` - Detect if Kotlin/jackson-module-kotlin is present
- `KotlinPropertyNamingStrategy` - Handle Kotlin property name conventions

## No Jackson Module Awareness

The reflection-free serializer code has **limited Jackson configuration awareness**:

**What it IS aware of:**
- ✅ `@JsonNaming` annotation on classes (checks at build time)
- ✅ `PropertyNamingStrategy` from ObjectMapper (queries at runtime for deserialization)

**What it is NOT aware of:**
- ❌ Jackson Modules (like `KotlinModule`)
- ❌ Custom `AnnotationIntrospector` implementations (like `KotlinNamesAnnotationIntrospector`)
- ❌ Module-specific property naming logic
- ❌ `ObjectMapperCustomizer` implementations (they run at runtime, after serializers are generated)

**The Fundamental Timing Problem:**

According to [Quarkus Kotlin documentation](https://quarkus.io/guides/kotlin#kotlin-jackson):
> If the `com.fasterxml.jackson.module:jackson-module-kotlin` dependency and the `quarkus-jackson` extension (or one of the `quarkus-resteasy-jackson` or `quarkus-rest-jackson` extensions) have been added to the project, then **Quarkus automatically registers the KotlinModule** to the ObjectMapper bean.

**How Quarkus registers KotlinModule:**
1. `KotlinProcessor` (in `quarkus-kotlin` extension) detects `jackson-module-kotlin` on classpath
2. Produces `ClassPathJacksonModuleBuildItem`
3. `JacksonProcessor` generates an `ObjectMapperCustomizer` that calls `objectMapper.registerModule(new KotlinModule())`
4. This customizer runs at **RUNTIME** when ObjectMapper is created

**Why reflection-free serializers break this:**
1. Reflection-free serializers are generated at **BUILD TIME** (see `ResteasyReactiveJacksonProcessor` line 398-399)
2. KotlinModule is registered at **RUNTIME**
3. Generated serializers are hardcoded with JavaBean conventions ("is" stripping)
4. When KotlinModule is later registered, it's too late - the serializers are already generated and cannot be changed

**Code locations proving this:**

**Build-time generation** (`ResteasyReactiveJacksonProcessor.java` line 398-400):
```java
@BuildStep(onlyIf = JacksonOptimizationConfig.IsReflectionFreeSerializersEnabled.class)
@Record(ExecutionTime.STATIC_INIT)  // ← BUILD TIME
public void handleEndpointParams(...) {
    // Generates serializers here
}
```

**Runtime KotlinModule registration** (`KotlinProcessor.java` line 32-39):
```java
@BuildStep
void registerKotlinJacksonModule(BuildProducer<ClassPathJacksonModuleBuildItem> classPathJacksonModules) {
    if (!QuarkusClassLoader.isClassPresentAtRuntime(KOTLIN_JACKSON_MODULE)) {
        return;
    }
    classPathJacksonModules.produce(new ClassPathJacksonModuleBuildItem(KOTLIN_JACKSON_MODULE));
    // This gets registered to ObjectMapper at runtime via generated ObjectMapperCustomizer
}
```

**Why this matters:**
`jackson-module-kotlin` works through:
- `KotlinModule` which registers `KotlinNamesAnnotationIntrospector`
- `KotlinNamesAnnotationIntrospector` extends `AnnotationIntrospector` (NOT `PropertyNamingStrategy`)
- Overrides `findImplicitPropertyName()` to return actual Kotlin property names

The reflection-free serializer generates code at **build time** using only:
- JavaBean conventions (hardcoded "is" stripping in `JacksonCodeGenerator.java` line 588)
- Annotations directly on classes/fields
- No runtime module inspection
- No awareness of what ObjectMapperCustomizers will be applied later

**This violates the documented behavior** - users following the Quarkus Kotlin documentation expect KotlinModule to work automatically, but reflection-free serializers bypass it entirely.

```bash
$ grep -r "AnnotationIntrospector\|ObjectMapperCustomizer" extensions/resteasy-reactive/rest-jackson/deployment/src/main/java/io/quarkus/resteasy/reactive/jackson/deployment/processor/
# No results in deployment processor
```

**Why this matters:**
`jackson-module-kotlin` works through:
- `KotlinModule` which registers `KotlinNamesAnnotationIntrospector`
- `KotlinNamesAnnotationIntrospector` extends `AnnotationIntrospector` (NOT `PropertyNamingStrategy`)
- Overrides `findImplicitPropertyName()` to return actual Kotlin property names

The reflection-free serializer generates code at **build time** using only:
- JavaBean conventions (hardcoded "is" stripping)
- Annotations directly on classes/fields
- No runtime module inspection

This is the fundamental issue - the code generator assumes all classes follow JavaBean conventions and cannot adapt to module-specific behavior.

```bash
$ grep -r "AnnotationIntrospector\|Module" extensions/resteasy-reactive/rest-jackson/deployment/src/main/java/
# No results
```

## Timeline

- **Pre-3.37**: Bug existed when `quarkus.rest.jackson.optimization.enable-reflection-free-serializers=true` was manually set
- **3.37**: Optimization enabled by default, exposing these bugs to all users
- **Root cause**: Reflection-free serializer implementation, NOT the Jackson version upgrade
