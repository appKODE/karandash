package io.karandash.cache.remote

import io.karandash.duration.KarandashDuration
import io.karandash.size.KarandashSize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for remote cache backends.
 *
 * ```yaml
 * cache:
 *   remote:
 *     backend: http
 *     url: "https://cache.example.com"
 *     push: true
 *     pull: true
 *     max-blob-size: "10mb"
 *     push-timeout: "5s"
 *     pull-timeout: "3s"
 * ```
 *
 * For `backend: s3`, [accessKeyId] and [secretAccessKey] (plus [sessionToken] for temporary credentials) turn on
 * SigV4 request signing for [region]. Without them the standard AWS chain signs (env `AWS_ACCESS_KEY_ID` +
 * `AWS_SECRET_ACCESS_KEY`, or the shared-file [profile] / `AWS_PROFILE` / `default`); with nothing found requests are
 * anonymous.
 */
@Serializable
data class RemoteCacheConfig(
    val backend: String,
    val url: String,
    val auth: String? = null,
    val push: Boolean = true,
    val pull: Boolean = true,
    val prefix: String = "",
    @SerialName("maxBlobSize") val maxBlobSize: KarandashSize = KarandashSize.ofMb(DEFAULT_MAX_BLOB_MB),
    @SerialName("pushTimeout") val pushTimeout: KarandashDuration = KarandashDuration.ofSeconds(DEFAULT_PUSH_TIMEOUT_S),
    @SerialName("pullTimeout") val pullTimeout: KarandashDuration = KarandashDuration.ofSeconds(DEFAULT_PULL_TIMEOUT_S),
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val region: String = DEFAULT_REGION,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val profile: String? = null,
    /** `cache gc --remote` also expires local entries older than this, deleting their blobs locally and on the remote. */
    val ttl: KarandashDuration? = null,
) {
    /** Never prints [auth], [secretAccessKey] or [sessionToken]. */
    override fun toString(): String =
        "RemoteCacheConfig(backend=$backend, url=$url, prefix=$prefix, region=$region, push=$push, pull=$pull, " +
            "accessKeyId=$accessKeyId, profile=$profile)"

    companion object {
        const val DEFAULT_REGION = "us-east-1"
        private const val DEFAULT_MAX_BLOB_MB = 10L
        private const val DEFAULT_PUSH_TIMEOUT_S = 5L
        private const val DEFAULT_PULL_TIMEOUT_S = 3L
    }
}
