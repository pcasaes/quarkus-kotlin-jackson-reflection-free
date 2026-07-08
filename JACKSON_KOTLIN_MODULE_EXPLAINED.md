# What Does jackson-module-kotlin Do?

## Overview

`jackson-module-kotlin` adds support for serialization/deserialization of Kotlin classes and data classes. It bridges the gap between Jackson's Java-centric design and Kotlin's unique language features.

**Without this module**, Jackson treats Kotlin classes as Java classes and misses Kotlin-specific features, leading to serialization/deserialization failures or incorrect behavior.

---

## Key Features

### 1. **Constructor-Based Deserialization** ✨

**Problem**: Standard Jackson requires a no-arg constructor. Kotlin data classes typically don't have one.

**Solution**: The module uses Kotlin reflection to deserialize directly into the primary constructor.

```kotlin
// Without jackson-module-kotlin: ❌ Fails - no default constructor
data class Person(val name: String, val age: Int)

// With jackson-module-kotlin: ✅ Works
val person = mapper.readValue<Person>("""{"name":"John","age":30}""")
```

### 2. **Default Parameter Values** ✨

**Problem**: Jackson doesn't understand Kotlin's default parameter values.

**Solution**: Missing JSON fields use Kotlin's declared defaults.

```kotlin
data class User(
    val name: String,
    val age: Int = 0,           // ✅ Uses 0 if missing in JSON
    val active: Boolean = true  // ✅ Uses true if missing in JSON
)

// JSON: {"name":"Alice"}
// Result: User(name="Alice", age=0, active=true)
```

### 3. **Nullable Type Handling** ✨

**Problem**: Jackson doesn't distinguish between Kotlin's nullable (`Type?`) and non-nullable (`Type`) types.

**Solution**: Properly handles null values based on Kotlin's type system.

```kotlin
data class Account(
    val id: String,      // ✅ Cannot be null
    val email: String?   // ✅ Can be null
)

// JSON with null: {"id":"123","email":null}
// With jackson-module-kotlin: ✅ Works
// Without: ❌ Might allow null where it shouldn't
```

### 4. **Boolean "is" Prefix Handling** ✨ *(This is what your bug was about!)*

**Problem**: Kotlin properties can start with "is" (like `isActive`), but Jackson's JavaBean conventions strip the "is" prefix.

**Solution**: Keeps the actual Kotlin property name.

```kotlin
data class Status(
    val isActive: Boolean,
    val isEligible: Boolean
)

// Without jackson-module-kotlin:
// Serializes as: {"active":true,"eligible":true}  ❌ Wrong!

// With jackson-module-kotlin:
// Serializes as: {"isActive":true,"isEligible":true}  ✅ Correct!
```

**This is exactly the issue documented in your `FINDINGS.md`!**

### 5. **Immutable Collections** ✨

**Problem**: Jackson deserializes to Java's mutable collections. Kotlin prefers immutable ones.

**Solution**: Deserializes to Kotlin's immutable `List`, `Set`, `Map`.

```kotlin
data class Team(
    val members: List<String>  // ✅ Immutable Kotlin List
)
```

### 6. **Sealed Classes Support** ✨

**Problem**: Polymorphic deserialization requires `@JsonSubTypes` annotation in standard Jackson.

**Solution**: Auto-detects all subclasses of sealed classes (known at compile-time).

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
sealed class Result {
    data class Success(val data: String) : Result()
    data class Error(val message: String) : Result()
}

// ✅ No @JsonSubTypes needed!
val result: Result = mapper.readValue(json)
```

### 7. **Value Classes (Inline Classes)** ✨

**Problem**: Kotlin value classes are compile-time wrappers that don't exist at runtime.

**Solution**: Properly unwraps/wraps value classes during serialization/deserialization.

```kotlin
@JvmInline
value class UserId(val value: String)

data class User(val id: UserId, val name: String)

// ✅ Serializes as: {"id":"user123","name":"John"}
// Not: {"id":{"value":"user123"},"name":"John"}
```

### 8. **Built-in Kotlin Types** ✨

Supports Kotlin-specific types:
- `Pair` → `{first, second}`
- `Triple` → `{first, second, third}`
- `IntRange`, `CharRange`, `LongRange`
- Object singletons

### 9. **Reified Generics Extension Functions** ✨

**Problem**: Java type erasure makes generic deserialization verbose.

**Solution**: Kotlin's reified generics allow clean syntax.

```kotlin
// Without module:
val person = mapper.readValue(json, Person::class.java)

// With module:
val person = mapper.readValue<Person>(json)  // ✅ Clean!
```

---

## How It Works Under The Hood

### Uses Kotlin Reflection

The module uses Kotlin's reflection API (`kotlin-reflect`) to:
1. **Inspect constructors** - Find the primary constructor and its parameters
2. **Read default values** - Extract default parameter values from Kotlin metadata
3. **Check nullability** - Determine if parameters are nullable (`Type?`)
4. **Get property names** - Use actual Kotlin property names (not JavaBean conventions)

### Custom AnnotationIntrospector

It registers `KotlinNamesAnnotationIntrospector` which:
- Overrides Jackson's default JavaBean property name logic
- Returns actual Kotlin property names
- Handles the "is" prefix correctly (doesn't strip it)

### Custom Deserializer

It provides `KotlinValueInstantiator` which:
- Calls Kotlin constructors with proper parameter mapping
- Applies default parameter values
- Validates nullability constraints

---

## Configuration Options

You can configure the module with `KotlinModule.Builder()`:

```kotlin
val kotlinModule = KotlinModule.Builder()
    .enable(KotlinFeature.StrictNullChecks)  // Fail on null for non-nullable types
    .build()

val mapper = jsonMapper {
    addModule(kotlinModule)
}
```

---

## Why Reflection-Free Serializers Break This

From your `FINDINGS.md`, the core problem is:

### The Timing Mismatch

```
BUILD TIME (Reflection-Free Generation):
├─ Generates serializers WITHOUT KotlinModule knowledge
├─ Uses JavaBean conventions (strips "is" prefix)
└─ No Kotlin reflection metadata used

RUNTIME (KotlinModule Registration):
├─ ObjectMapperCustomizer runs
├─ Registers KotlinModule
└─ But serializers already generated - too late!
```

**jackson-module-kotlin works by:**
- Registering custom `AnnotationIntrospector`
- Providing custom `ValueInstantiator`
- Using Kotlin reflection at runtime

**Reflection-free serializers work by:**
- Generating bytecode at **build time**
- Using hardcoded JavaBean conventions
- No runtime introspection

**These are fundamentally incompatible architectures!**

---

## What PR #55216 Fixed

The Quarkus fix added a **build-time workaround**:

```java
private static final DotName KOTLIN_METADATA = DotName.createSimple("kotlin.Metadata");

// During serializer generation:
if (classInfo.hasDeclaredAnnotation(KOTLIN_METADATA)) {
    // Don't apply JavaBean "is" stripping
    // Use property name as-is
}
```

**This partially emulates what KotlinModule does**, but only for:
- ✅ Boolean "is" prefix handling
- ✅ Property name detection

**It does NOT provide:**
- ❌ Default parameter values
- ❌ Nullable type validation
- ❌ Sealed class auto-detection
- ❌ Value class unwrapping
- ❌ Full Kotlin reflection-based introspection

---

## Summary Table

| Feature | Without jackson-module-kotlin | With jackson-module-kotlin | With Reflection-Free (Post PR #55216) |
|---------|-------------------------------|----------------------------|---------------------------------------|
| Constructor deserialization | ❌ Requires no-arg constructor | ✅ Uses primary constructor | ✅ Works |
| Default parameter values | ❌ Ignored | ✅ Applied | ❌ Ignored |
| Nullable type checking | ❌ Not enforced | ✅ Enforced | ❌ Not enforced |
| Boolean "is" prefix | ❌ Strips to "active" | ✅ Keeps "isActive" | ⚠️ **Fixed for Kotlin classes** |
| Immutable collections | ❌ Mutable Java collections | ✅ Immutable Kotlin collections | ❌ Mutable Java collections |
| Sealed classes | ❌ Requires @JsonSubTypes | ✅ Auto-detected | ❌ Requires @JsonSubTypes |
| Value classes | ❌ Broken | ✅ Unwrapped | ❌ Likely broken |
| Reified generics | ❌ Not available | ✅ Available | ❌ Not available |

---

## Bottom Line

**jackson-module-kotlin is essential for proper Kotlin serialization/deserialization** because:
1. It understands Kotlin's type system (nullability, defaults)
2. It respects Kotlin's property naming (no "is" stripping)
3. It works with Kotlin's language features (sealed classes, value classes)
4. It uses Kotlin reflection to access metadata

**However, it's fundamentally incompatible with reflection-free serializers** because:
- Reflection-free = build-time bytecode generation
- jackson-module-kotlin = runtime reflection-based introspection

**PR #55216 adds a partial workaround** for Kotlin detection at build time, fixing the most critical issues (like "is" prefix handling) but not providing full feature parity with the module.
