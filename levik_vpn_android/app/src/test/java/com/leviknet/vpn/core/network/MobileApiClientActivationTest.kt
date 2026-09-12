package com.leviknet.vpn.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileApiClientActivationTest {
    @Test
    fun `uses authenticated mobile activation route and canonical request body`() {
        assertEquals(
            "/api/mobile/v1/activation/authorize",
            MobileApiClient.ACTIVATION_AUTHORIZE_PATH,
        )
        assertEquals(
            "{\"code\":\"ABCD-EFGH-JKMN-PQRS\"}",
            Json.encodeToString(
                MobileAuthorizeActivationRequest("ABCD-EFGH-JKMN-PQRS"),
            ),
        )
    }
}
