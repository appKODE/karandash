package io.karandash.http

import io.karandash.testfixtures.TestFiles
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PemBundleTrustManagerTest {
    private val systemCa =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
            .acceptedIssuers
            .first()

    @Test
    fun trustsExactlyTheCertificatesOfThePemBundle() {
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(systemCa.encoded) +
            "\n-----END CERTIFICATE-----\n"
        val bundle = java.nio.file.Path
            .of(TestFiles.createTestTempDirectory("karandash-ca").toString(), "ca.pem")
            .also { Files.writeString(it, pem) }

        val issuers = PemBundleTrustManager(bundle.toString()).acceptedIssuers

        assertEquals(listOf(systemCa), issuers.toList())
    }

    @Test
    fun unreadableBundleFailsTheHandshakeAsACertificateError() {
        val manager = PemBundleTrustManager("/nonexistent/karandash-ca.pem")

        val error = assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(systemCa), "RSA") }

        assertContains(error.message.orEmpty(), "/nonexistent/karandash-ca.pem")
    }
}
