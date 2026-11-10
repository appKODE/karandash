package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import java.security.cert.CertificateException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineTransportFailureTest {
    @Test
    fun untrustedCertificateSurfacesAsIOException() =
        runTest {
            val engine = MockEngine { throw CertificateException("PKIX path building failed") }
            val client = ProviderHttpClientFactory.create(engine = engine)

            val error = assertFailsWith<IOException> { client.get("https://config.example.com/base.yaml") }

            assertTrue("config.example.com" in error.message.orEmpty(), error.message)
            assertTrue(error.cause is CertificateException)
            client.close()
        }
}
