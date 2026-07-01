package com.zwift.reproducer

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class TestWithJsonPropertyDto(
    @field:JsonProperty("name")
    val field: String,
)
