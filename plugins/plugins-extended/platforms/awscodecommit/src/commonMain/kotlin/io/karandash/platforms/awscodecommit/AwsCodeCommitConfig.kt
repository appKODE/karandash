package io.karandash.platforms.awscodecommit

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for AWS CodeCommit mutator and comment-poster modules.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AwsCodeCommitConfig(
    val region: String = "us-east-1",
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    /** Shared-file profile (`~/.aws/credentials`, `~/.aws/config`) to take credentials from; overrides `AWS_PROFILE` and env keys. */
    val profile: String? = null,
    /** CodeCommit API root replacing `https://codecommit.<region>.amazonaws.com` (VPC endpoint, proxy); SSRF-checked. */
    @JsonNames("url")
    val endpoint: String? = null,
    /** STS API root replacing `https://sts.<region>.amazonaws.com` for the credential check; SSRF-checked. */
    val stsEndpoint: String? = null,
) : PlatformServiceConfig {
    companion object
}
