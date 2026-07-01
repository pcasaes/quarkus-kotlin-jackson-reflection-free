# Quarkus Kotlin Jackson Reflection-Free Reproducer

This project reproduces two bugs in Quarkus's reflection-free Jackson serializers when used with Kotlin.

## The Issues

**Note**: These issues exist in Quarkus 3.36 and possibly earlier versions when the optimization is manually enabled. In Quarkus 3.37, the optimization was enabled **by default**, making these bugs affect all users (commit: 8b303b992c684086abf7db55835270c77dfc0cbd).

### Issue 1: Boolean Properties with "is" Prefix

When using Kotlin data classes with boolean properties starting with "is" (e.g., `isEligible`), the behavior differs based on whether `jackson-module-kotlin` is present:

**Without jackson-module-kotlin:**
- ✅ Serialization works, but "is" prefix is dropped from JSON field name
- `val isEligible: Boolean` → `{"eligible": true}`

**With jackson-module-kotlin (reflection-free serializers enabled):**
- ❌ Serialization fails to deserialize back
- Serializes as `{"isEligible": true}` but expects `{"eligible": true}` for deserialization
- Results in: `UnrecognizedPropertyException: Unrecognized field "isEligible"`

**With jackson-module-kotlin (reflection-free serializers disabled):**
- ✅ Works correctly with "is" prefix handling

### Issue 2: @JsonProperty Annotation

When using `@JsonProperty` to rename fields, the reflection-free serializers produce **duplicate fields** in the JSON output.

**Example:**
```kotlin
data class TestDto(
    @JsonProperty("name")
    val field: String
)
```

**Expected JSON:**
```json
{"name": "value"}
```

**Actual JSON with reflection-free serializers enabled:**
```json
{"name": "value", "field": "value"}
```

Both the annotated name (`name`) and the property name (`field`) are included in the output, regardless of whether `jackson-module-kotlin` is present.

**With reflection-free serializers disabled:**
- ✅ Works correctly - only `{"name": "value"}` is output

## Project Structure

- `TestWithIsDto.kt` - Kotlin data class with `isEligible: Boolean` property (no annotation)
- `TestWithJsonPropertyDto.kt` - Kotlin data class with `@JsonProperty("name")` on `field` property
- `TestResource.kt` - REST endpoints that return both DTOs
- `TestResourceWithReflectionFreeEnabledTest.kt` - Tests with reflection-free serializers **enabled** (demonstrates bugs)
- `TestResourceWithReflectionFreeDisabledTest.kt` - Tests with reflection-free serializers **disabled** (works correctly)
- `ReflectionFreeEnabledProfile.kt` - QuarkusTestProfile that enables reflection-free serializers
- `ReflectionFreeDisabledProfile.kt` - QuarkusTestProfile that disables reflection-free serializers

## How to Test

### Run All Tests
```bash
mvn clean test
```

You will see:
- `TestResourceWithReflectionFreeEnabledTest` - Demonstrates both bugs
- `TestResourceWithReflectionFreeDisabledTest` - Shows correct behavior with reflection-based serializers

### Run Individual Tests
```bash
# Test with reflection-free serializers enabled (demonstrates bugs)
mvn test -Dtest=TestResourceWithReflectionFreeEnabledTest

# Test with reflection-free serializers disabled (works correctly)
mvn test -Dtest=TestResourceWithReflectionFreeDisabledTest
```

### Observe JSON Output
Both tests print the JSON output. You will see:

**With reflection-free serializers enabled:**
- Issue 1: `{"isEligible": true, "name": "value"}` (with jackson-module-kotlin)
- Issue 2: `{"name": "value", "field": "value"}` (duplicate fields)

**With reflection-free serializers disabled:**
- Issue 1: `{"eligible": true, "name": "value"}` (correct - "is" prefix removed)
- Issue 2: `{"name": "value"}` (correct - only annotated name)

## Impact

### Issue 1 Impact
Any Kotlin service using:
- Quarkus 3.36+ (with optimization enabled) or Quarkus 3.37+ (enabled by default)
- jackson-module-kotlin dependency  
- Boolean properties with "is" prefix

Will experience **round-trip serialization failures**.

### Issue 2 Impact  
Any Kotlin service using:
- Quarkus 3.36+ (with optimization enabled) or Quarkus 3.37+ (enabled by default)
- `@JsonProperty` annotations to rename fields

Will experience:
- Send duplicate fields in JSON responses (potential security/data leakage issue)
- Violate API contracts expecting specific field names only
- Increase payload size unnecessarily

## Workarounds

### Primary Workaround: Disable Reflection-Free Serializers
```properties
quarkus.rest.jackson.optimization.enable-reflection-free-serializers=false
```

### Issue 1 Specific Workarounds:
- **Option 1**: Remove "is" prefix from property name (`eligible` instead of `isEligible`)
- **Option 2**: Remove `jackson-module-kotlin` dependency (but this breaks other Kotlin features)

### Issue 2 Specific Workarounds:
- **Option 1**: Rename property to match desired JSON field name (no `@JsonProperty` needed)
- **Option 2**: None - must disable reflection-free serializers

## Related Information

- **Quarkus Version**: 3.37.0 (bug also present in 3.36 and possibly earlier when optimization is enabled)
- **Kotlin Version**: 2.4.0
- **Jackson Version**: 2.22.0 (bundled with Quarkus 3.37)
- **jackson-module-kotlin**: Issue 1 only occurs with this dependency; Issue 2 occurs with or without it
- **Commit enabling reflection-free serializers by default**: https://github.com/quarkusio/quarkus/commit/8b303b992c684086abf7db55835270c77dfc0cbd (Quarkus 3.37)
- **Jackson upgrade in Quarkus 3.37**: https://github.com/quarkusio/quarkus/commit/ddb9c5bc9adacd6bd4b9f18fc11f9745156d84b5 (2.21.4 → 2.22.0)

## Important Notes

- **These bugs existed before Quarkus 3.37** when `quarkus.rest.jackson.optimization.enable-reflection-free-serializers=true` was manually set
- **Quarkus 3.37 changed the default** from `false` to `true`, exposing these bugs to all users by default
- The bugs are in the reflection-free serializer implementation, not in the Jackson version upgrade

## Summary

The reflection-free serializers in Quarkus have two critical issues with Kotlin:
1. Cannot properly handle boolean properties with "is" prefix when `jackson-module-kotlin` is present
2. Generate duplicate fields when `@JsonProperty` is used to rename properties

Both issues are resolved by disabling reflection-free serializers.

**Timeline:**
- These bugs existed in Quarkus 3.36 (and possibly earlier) when the optimization was manually enabled
- Quarkus 3.37 enabled the optimization **by default**, exposing these bugs to all users
- The root cause is in the reflection-free serializer code generation, not the Jackson library version



