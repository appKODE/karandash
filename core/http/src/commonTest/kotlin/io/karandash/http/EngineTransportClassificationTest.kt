package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Only TLS/transport failures become `IOException` (E0009); any other engine error keeps its own type, so it is not wrapped as `IOException`. */
class EngineTransportClassificationTest {
    @Test
    fun otherIllegalStateIsNotTransportFailure() {
        assertFalse(IllegalStateException("Connection was reset by a handler").isEngineTransportFailure())
        assertFalse(IllegalStateException("verification of TLS failed").isEngineTransportFailure())
        assertFalse(IllegalStateException(null as String?).isEngineTransportFailure())
        assertFalse(IllegalArgumentException("TLS verification failed").isEngineTransportFailure())
    }

    @Test
    fun otherEngineErrorIsNotWrappedAsIOException() =
        runTest {
            val engine = MockEngine { error("unexpected engine state") }
            val client = ProviderHttpClientFactory.create(engine = engine)

            val error = assertFailsWith<IllegalStateException> { client.get("https://api.example.com/x") }

            assertEquals("unexpected engine state", error.message)
            client.close()
        }
}
