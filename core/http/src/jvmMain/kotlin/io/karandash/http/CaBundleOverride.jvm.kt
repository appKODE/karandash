package io.karandash.http

import io.karandash.platform.PlatformEnvironment
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import java.io.FileInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal actual fun HttpClientConfig<*>.configureDefaultEngine(insecure: Boolean) {
    val trust = if (insecure) TrustAllManager else caBundleOverride(PlatformEnvironment::getEnv)?.let(::PemBundleTrustManager)
    if (trust == null) return
    engine {
        (this as? CIOEngineConfig)?.https { trustManager = trust }
    }
}

internal actual fun Throwable.isEngineTransportFailure(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is GeneralSecurityException }

private const val MAX_CAUSE_DEPTH = 8

/**
 * Accepts any certificate chain ([TLS_INSECURE_HOSTS_ENV]). CIO still checks the host name against the certificate, so on
 * the JVM this reaches a self-signed or expired server by its name, not a server by IP.
 */
internal object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Trusts exactly the certificates of the PEM bundle at [path], as curl's `CAINFO` does. The bundle is read on the
 * first handshake: an unreadable one fails that request with a [CertificateException] (a transport failure), not
 * client creation.
 */
internal class PemBundleTrustManager(
    private val path: String,
) : X509TrustManager {
    private val delegate: X509TrustManager by lazy { load() }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = delegate.checkServerTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun load(): X509TrustManager {
        val certificates = try {
            FileInputStream(path).use { CertificateFactory.getInstance(X509).generateCertificates(it) }
        } catch (e: IOException) {
            throw CertificateException("CA bundle $path (SSL_CERT_FILE / CURL_CA_BUNDLE) is unreadable: ${e.message}", e)
        }
        if (certificates.isEmpty()) throw CertificateException("CA bundle $path (SSL_CERT_FILE / CURL_CA_BUNDLE) holds no certificates")
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        const val X509 = "X.509"
    }
}
