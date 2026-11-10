package io.karandash.http

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMessageBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * The one credential type for outgoing HTTP requests: platforms, enrichment providers, the plugin registry,
 * `extends:` fetches and `auth:` content sources all carry an [HttpAuth]. Which host may receive it is decided by
 * [HostCredentials]; [applyTo] itself does not check the host.
 *
 * Serialized with a `type` discriminator (`bearer-token`, `basic-auth`, `custom-header`) - the `extends[].auth` form.
 * [toString] never prints the secret.
 */
@Serializable
sealed class HttpAuth {
    /** The request header this credential sets. */
    abstract val headerName: String

    /** The value of [headerName]. */
    abstract fun headerValue(): String

    /** Sets [headerName] on [request], replacing any value already there. */
    fun applyTo(request: HttpMessageBuilder) {
        request.headers[headerName] = headerValue()
    }

    /** Bearer token authentication (Authorization: Bearer <token>). */
    @Serializable
    @SerialName("bearer-token")
    data class Bearer(
        val token: String
    ) : HttpAuth() {
        override val headerName: String get() = HttpHeaders.Authorization

        override fun headerValue(): String = "Bearer $token"

        override fun toString() = "Bearer(token=***)"
    }

    /** Basic authentication (Authorization: Basic <base64>). */
    @Serializable
    @SerialName("basic-auth")
    data class Basic(
        val username: String,
        val password: String
    ) : HttpAuth() {
        override val headerName: String get() = HttpHeaders.Authorization

        override fun headerValue(): String = "Basic ${Base64.encode("$username:$password".encodeToByteArray())}"

        override fun toString() = "Basic(username=$username, password=***)"
    }

    /** Custom header authentication (e.g., PRIVATE-TOKEN, X-API-Key, `Authorization: token <t>`). */
    @Serializable
    @SerialName("custom-header")
    data class CustomHeader(
        val name: String,
        val value: String
    ) : HttpAuth() {
        override val headerName: String get() = name

        override fun headerValue(): String = value

        override fun toString() = "CustomHeader(name=$name, value=***)"
    }
}

/**
 * Extension to apply optional [HttpAuth] to an [HttpRequestBuilder].
 */
fun HttpRequestBuilder.applyAuth(auth: HttpAuth?) {
    auth?.applyTo(this)
}
