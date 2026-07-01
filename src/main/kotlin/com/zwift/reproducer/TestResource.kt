package com.zwift.reproducer

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TestResource {

    @GET
    @Path("/with-is")
    fun withIs(): TestWithIsDto {
        return TestWithIsDto(
            isEligible = true,
            name = "value"
        )
    }

    @GET
    @Path("/with-json-property")
    fun withJsonProperty(): TestWithJsonPropertyDto {
        return TestWithJsonPropertyDto(
            field = "value"
        )
    }
}
