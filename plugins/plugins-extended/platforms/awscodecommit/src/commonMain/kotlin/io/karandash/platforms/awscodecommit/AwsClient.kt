package io.karandash.platforms.awscodecommit

/**
 * Credential holder for AWS API access.
 *
 * Values come from [AwsCredentials.resolve] (config, then the standard AWS env/shared-file chain). Only answers
 * whether credentials exist for the auth check; signing (with the session token) happens in [AwsCodeSuiteClient].
 *
 * HTTP client creation is deferred until actual API calls are needed.
 */
class AwsClient(
    private val region: String? = null,
    private val accessKeyId: String? = null,
    private val secretAccessKey: String? = null,
) {
    /**
     * Get the resolved region for this AWS client.
     */
    fun getRegion(): String? = region

    /**
     * Get the resolved access key ID for this AWS client.
     */
    fun getAccessKeyId(): String? = accessKeyId

    /**
     * Check if credentials are configured.
     */
    fun hasCredentials(): Boolean = accessKeyId != null && secretAccessKey != null
}
