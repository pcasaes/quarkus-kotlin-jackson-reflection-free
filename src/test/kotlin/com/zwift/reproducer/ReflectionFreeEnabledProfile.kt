package com.zwift.reproducer

import io.quarkus.test.junit.QuarkusTestProfile

class ReflectionFreeEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            "quarkus.rest.jackson.optimization.enable-reflection-free-serializers" to "true"
        )
    }
}
