package io.karandash.platforms.jenkins

import io.karandash.http.HttpAuth
import kotlinx.serialization.Serializable

/**
 * Jenkins API credentials, used to read the build result from the Jenkins JSON API.
 *
 * Merge request data still comes from the underlying platform provider; these credentials only authenticate
 * `/api/json` calls on the Jenkins server. [user] with [token] gives basic auth, as Jenkins API tokens expect.
 */
@Serializable
data class JenkinsCredentials(
    val token: String?,
    val user: String? = null,
) {
    /** Basic auth when both user and token are set, otherwise none (anonymous read). */
    fun auth(): HttpAuth? = if (!user.isNullOrBlank() && !token.isNullOrBlank()) HttpAuth.Basic(user, token) else null

    companion object {
        /** `JENKINS_USER` plus `JENKINS_API_TOKEN` (or `JENKINS_TOKEN`). */
        fun resolve(env: (String) -> String?): JenkinsCredentials =
            JenkinsCredentials(
                token = env("JENKINS_API_TOKEN") ?: env("JENKINS_TOKEN"),
                user = env("JENKINS_USER"),
            )
    }
}
