package com.zwift.reproducer

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(ReflectionFreeEnabledProfile::class)
class TestResourceWithReflectionFreeEnabledTest {

    @Test
    fun `test with field name with is prefix`() {
        val extract = given()
            .contentType(ContentType.JSON)
            .get("/test/with-is")
            .then()
            .statusCode(200)
            .extract()

        val string = extract.asString()
        println(string)

        extract
            .body()
            .`as`(TestWithIsDto::class.java)
    }


    @Test
    fun `test with field name with JsonProperty`() {
        val extract = given()
            .contentType(ContentType.JSON)
            .get("/test/with-json-property")
            .then()
            .statusCode(200)
            .extract()

        val string = extract.asString()
        println(string)

        val dto = extract.`as`(TestWithJsonPropertyDto::class.java)
        assertEquals("value", dto.field)
    }
}
