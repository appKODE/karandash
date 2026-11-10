package io.karandash.platforms.awscodecommit

import io.karandash.crypto.AwsCredentialChain
import io.karandash.crypto.AwsStaticCredentials
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object AwsCredentials {
    /** Resolved region, credentials and endpoints; [toString] never prints the secret or the session token. */
    @Serializable
    data class Resolved(
        val region: String,
        val accessKeyId: String?,
        val secretAccessKey: String?,
        val sessionToken: String?,
        val endpoint: String? = null,
        val stsEndpoint: String? = null,
    ) {
        override fun toString(): String =
            "Resolved(region=$region, accessKeyId=$accessKeyId, secretAccessKey=***, sessionToken=***, " +
                "endpoint=$endpoint, stsEndpoint=$stsEndpoint)"
    }

    /**
     * Resolve AWS credentials from config, then the standard AWS chain ([AwsCredentialChain]).
     *
     * Precedence:
     * - Region: config.region > AWS_REGION > AWS_DEFAULT_REGION > profile `region` > "us-east-1"
     * - Credentials: config.accessKeyId + config.secretAccessKey (+ config.sessionToken) when both are set, else the
     *   chain (config.profile's shared files; without it env keys, then the `AWS_PROFILE`/`default` shared files).
     *   Key and secret always come from the same source. The chain is used only while `endpoint` and `stsEndpoint`
     *   are unset: any `*.amazonaws.com` host (API Gateway, EC2, ELB) can be someone else's, so ambient credentials
     *   only ever sign for the default AWS endpoints.
     */
    fun resolve(
        config: JsonElement,
        chain: AwsCredentialChain,
        logger: Logger = NoOpLogger,
    ): Resolved {
        val obj = config as? JsonObject ?: JsonObject(emptyMap())
        val cfg = KarandashJson.Default.decodeFromJsonElement(AwsCodeCommitConfig.serializer(), obj)
        val region = if (obj.containsKey("region")) cfg.region else chain.region(cfg.profile)
        val explicit = explicit(cfg.accessKeyId, cfg.secretAccessKey, cfg.sessionToken)
        val creds = if (explicit != null) {
            explicit
        } else if (cfg.endpoint == null && cfg.stsEndpoint == null) {
            chain.credentials(cfg.profile)
        } else {
            logger.warn(
                "AWS CodeCommit: endpoint/stsEndpoint is set, so ambient AWS credentials (env, ~/.aws) " +
                    "are not sent there; set accessKeyId and secretAccessKey to use it",
            )
            null
        }
        return Resolved(
            region = region,
            accessKeyId = creds?.accessKeyId,
            secretAccessKey = creds?.secretAccessKey,
            sessionToken = creds?.sessionToken,
            endpoint = cfg.endpoint,
            stsEndpoint = cfg.stsEndpoint,
        )
    }

    /** Static credentials when both key and secret are set (a blank session token counts as none), else null. */
    fun explicit(
        accessKeyId: String?,
        secretAccessKey: String?,
        sessionToken: String?,
    ): AwsStaticCredentials? {
        val id = accessKeyId?.takeIf { it.isNotBlank() } ?: return null
        val secret = secretAccessKey?.takeIf { it.isNotBlank() } ?: return null
        return AwsStaticCredentials(id, secret, sessionToken?.takeIf { it.isNotBlank() })
    }
}
