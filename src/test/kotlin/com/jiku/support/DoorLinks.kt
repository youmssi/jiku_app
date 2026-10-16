package com.jiku.support

import com.jayway.jsonpath.JsonPath
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * The signed token behind a door link (JIKU-250): the link carries a short code,
 * exchanged the way the door page does before calling `/checkin/{token}`.
 */
object DoorLinks {
    fun token(
        mockMvc: MockMvc,
        link: String,
    ): String {
        val code = link.substringAfterLast("/p/")
        val body =
            mockMvc
                .perform(get("/api/v1/operator-codes/$code").param("door", "true"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.token")
    }
}
