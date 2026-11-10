package io.karandash.providers.common

import io.karandash.api.platform.CIEnvironment

/**
 * CI systems that build for more than one forge, so each forge's platform recognises its own builds here instead of
 * every platform guessing: Woodpecker (`CI=woodpecker`, the forge named by `CI_FORGE_TYPE`) and AWS CodeBuild with a
 * GitHub, GitLab or Bitbucket source (`CODEBUILD_SOURCE_REPO_URL`, a pull request build named `pr/<n>`).
 * No token: neither system hands one to the build, so the platform's configured token is used.
 */
object ForgeCiEnvironment {
    private val PR_REF = Regex("""(?:^|/)pr/(\d+)""")

    /**
     * The Woodpecker build when its forge is one of [forgeTypes]; [baseUrl] maps the forge's web URL
     * (`CI_FORGE_URL`) to the platform's base URL.
     */
    fun woodpecker(
        env: (String) -> String?,
        platform: String,
        forgeTypes: Set<String>,
        baseUrl: (String) -> String = { it },
    ): CIEnvironment? {
        if (env("CI") != "woodpecker" || env("CI_FORGE_TYPE") !in forgeTypes) return null
        return CIEnvironment(
            platform = platform,
            projectId = env("CI_REPO"),
            mrId = env("CI_COMMIT_PULL_REQUEST"),
            token = null,
            baseUrl = env("CI_FORGE_URL")?.trimEnd('/')?.let(baseUrl),
        )
    }

    /**
     * The CodeBuild build when [projectIdOf] (the platform's git remote parser) recognises its source repository URL.
     */
    suspend fun codeBuild(
        env: (String) -> String?,
        platform: String,
        baseUrl: String?,
        projectIdOf: suspend (String) -> String?,
    ): CIEnvironment? {
        env("CODEBUILD_BUILD_ID") ?: return null
        val projectId = env("CODEBUILD_SOURCE_REPO_URL")?.let { projectIdOf(it) } ?: return null
        val mrId = listOfNotNull(env("CODEBUILD_WEBHOOK_TRIGGER"), env("CODEBUILD_SOURCE_VERSION"))
            .firstNotNullOfOrNull { PR_REF.find(it)?.groupValues?.get(1) }
        return CIEnvironment(platform = platform, projectId = projectId, mrId = mrId, token = null, baseUrl = baseUrl)
    }
}
