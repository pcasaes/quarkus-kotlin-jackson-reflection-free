# Test Results Summary

## Reproducing the Issues

This reproducer demonstrates **two bugs** in Quarkus's reflection-free Jackson serializers when used with Kotlin.

**Important**: These bugs existed in Quarkus 3.36 (and possibly earlier) when the optimization was manually enabled. Quarkus 3.37 enabled it **by default**, exposing these bugs to all users.

## Test Execution

### Run All Tests
```bash
mvn clean test
```

## The Two Issues

### Issue 1: Boolean Properties with "is" Prefix

#### Without jackson-module-kotlin
- Serialization: `{"eligible": true, "name": "value"}` (✅ "is" prefix dropped)
- Problem: Loses semantic meaning - property name `isEligible` becomes `eligible`

#### With jackson-module-kotlin + Reflection-Free Serializers (ENABLED)
- Serialization: `{"isEligible": true, "name": "value"}` 
- Deserialization: ❌ **FAILS** - expects `{"eligible": true}`
- Error: `UnrecognizedPropertyException: Unrecognized field "isEligible"`
- Problem: Round-trip serialization is broken

#### With jackson-module-kotlin + Reflection-Free Serializers (DISABLED)
- ✅ Works correctly with proper "is" prefix handling

### Issue 2: @JsonProperty Annotation Produces Duplicate Fields

#### Code
```kotlin
data class TestWithJsonPropertyDto(
    @JsonProperty("name")
    val field: String
)
```

#### With Reflection-Free Serializers (ENABLED)
- Expected: `{"name": "value"}`
- Actual: `{"name": "value", "field": "value"}` ❌
- Problem: Both the annotated name AND the property name appear in JSON

#### With Reflection-Free Serializers (DISABLED)  
- Actual: `{"name": "value"}` ✅
- Works correctly - only annotated name appears

## Test Classes

### TestResourceWithReflectionFreeEnabledTest
Tests both issues with reflection-free serializers **enabled** (default in Quarkus 3.37).

**Test: `test with field name with is prefix`**
- Demonstrates Issue 1
- With jackson-module-kotlin: Fails deserialization

**Test: `test with field name with JsonProperty`**  
- Demonstrates Issue 2
- JSON contains duplicate fields: `{"name": "value", "field": "value"}`

### TestResourceWithReflectionFreeDisabledTest
Tests both cases with reflection-free serializers **disabled**.

**Test: `test with field name with is prefix`**
- ✅ Works correctly with jackson-module-kotlin

**Test: `test with field name with JsonProperty`**
- ✅ Works correctly - only `{"name": "value"}`

## Root Causes

### Issue 1: "is" Prefix Handling
1. The reflection-free serializer code generator does not properly coordinate with `jackson-module-kotlin` for boolean "is" prefix handling
2. Serialization uses one field name (`isEligible`), deserialization expects another (`eligible`)
3. This bug existed in Quarkus 3.36+, but became default behavior in 3.37

### Issue 2: @JsonProperty Duplicate Fields
1. The reflection-free serializer generator includes both:
   - The field with the `@JsonProperty` name
   - The field with the original property name
2. This happens regardless of whether `jackson-module-kotlin` is present
3. Creates duplicate fields in JSON output

## Impact

### Issue 1 Impact
Any Kotlin service using:
- Quarkus 3.36+ (with `quarkus.rest.jackson.optimization.enable-reflection-free-serializers=true`)
- **OR** Quarkus 3.37+ (optimization enabled by default)
- jackson-module-kotlin dependency  
- Boolean properties with "is" prefix

Will experience **round-trip serialization failures**.

### Issue 2 Impact
Any Kotlin service using:
- Quarkus 3.36+ (with `quarkus.rest.jackson.optimization.enable-reflection-free-serializers=true`)
- **OR** Quarkus 3.37+ (optimization enabled by default)
- `@JsonProperty` annotations to rename fields

Will experience:
- **Duplicate fields in JSON responses** (potential data leakage)
- **API contract violations** (unexpected fields in output)
- **Increased payload size**
- **Potential security issues** (exposing internal property names)

## Workarounds

### Primary Workaround: Disable Reflection-Free Serializers (Recommended)
```properties
quarkus.rest.jackson.optimization.enable-reflection-free-serializers=false
```
This fixes **both issues**.

### Issue 1 Specific Workarounds:
- **Option 1**: Remove "is" prefix from property names (`eligible` instead of `isEligible`)
- **Option 2**: Remove `jackson-module-kotlin` dependency (loses Kotlin-specific features)
- **Option 3**: Use `@JsonProperty` to force field name (but see Issue 2!)

### Issue 2 Specific Workarounds:
- **Option 1**: Rename property to match desired JSON field name (avoid `@JsonProperty`)
- **Option 2**: None - must disable reflection-free serializers

## Related Commits

- **Reflection-free serializers enabled by default (3.37)**: https://github.com/quarkusio/quarkus/commit/8b303b992c684086abf7db55835270c77dfc0cbd
- **Jackson 2.22.0 upgrade**: https://github.com/quarkusio/quarkus/commit/ddb9c5bc9adacd6bd4b9f18fc11f9745156d84b5

## Conclusion

The reflection-free serializers in Quarkus have critical compatibility issues with Kotlin:
1. **Cannot handle "is" prefix** on boolean properties when `jackson-module-kotlin` is present
2. **Generates duplicate fields** when `@JsonProperty` is used

**Important Timeline:**
- Bugs existed in Quarkus 3.36 (possibly earlier) when optimization was manually enabled
- Quarkus 3.37 made the optimization **default**, exposing these bugs to all users
- Root cause is in the reflection-free serializer implementation, not Jackson version

**Recommendation**: Disable reflection-free serializers until these issues are fixed.

