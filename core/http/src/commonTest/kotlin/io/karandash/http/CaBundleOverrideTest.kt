package io.karandash.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaBundleOverrideTest {
    @Test
    fun sslCertFileWinsOverCurlCaBundle() {
        val env = mapOf("SSL_CERT_FILE" to "/a.pem", "CURL_CA_BUNDLE" to "/b.pem")
        assertEquals("/a.pem", caBundleOverride(env::get))
    }

    @Test
    fun curlCaBundleUsedWhenSslCertFileBlank() {
        val env = mapOf("SSL_CERT_FILE" to " ", "CURL_CA_BUNDLE" to "/b.pem")
        assertEquals("/b.pem", caBundleOverride(env::get))
    }

    @Test
    fun noOverrideWhenUnset() {
        assertNull(caBundleOverride { null })
    }
}
