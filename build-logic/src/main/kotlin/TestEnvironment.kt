/**
 * Test-process environment applied by `karandash.kmp-library` to every JVM `Test` and `KotlinNativeTest` task.
 *
 * - [UPDATE_FLAGS]: `-Dkarandash.updateGolden` / `-Dkarandash.updateBaseline` on the Gradle invocation become env
 *   vars ("true"/"false"), so `GoldenFiles` in commonTest reads one switch on every target.
 * - [NATIVE_BLANKED]: native tests must never go live. The Kotlin/Native test runner always inherits the daemon
 *   environment and cannot remove a variable, so these are overridden with an empty value (`getEnv` -> "").
 * - [CI_DETECTION]: the CI job's own env (GitLab MR pipeline, GitHub Actions, ...) would make every test CLI run
 *   auto-detect that MR and post to it. Removed from JVM `Test` processes, blanked for native ones
 *   (`CIDetector` treats a blank value as unset).
 */
object TestEnvironment {
    val UPDATE_FLAGS: Map<String, String> =
        mapOf(
            "karandash.updateGolden" to "KARANDASH_UPDATE_GOLDEN",
            "karandash.updateBaseline" to "KARANDASH_UPDATE_BASELINE",
        )

    val NATIVE_BLANKED: List<String> =
        listOf(
            "KARANDASH_EVAL_LIVE",
            // platform tokens
            "GITLAB_TOKEN",
            "GITLAB_APPROVE_TOKEN",
            "CI_JOB_TOKEN",
            "GITHUB_TOKEN",
            "GITEA_TOKEN",
            "GERRIT_TOKEN",
            "BITBUCKET_TOKEN",
            "BITBUCKET_ACCESS_TOKEN",
            "AZURE_DEVOPS_TOKEN",
            "SYSTEM_ACCESSTOKEN",
            "JIRA_TOKEN",
            "CONFLUENCE_TOKEN",
            "KARANDASH_TOKEN",
            // AI provider keys
            "ANTHROPIC_API_KEY",
            "OPENAI_API_KEY",
            "GEMINI_API_KEY",
            "LITELLM_API_KEY",
            "BIFROST_API_KEY",
            // credential entries of KarandashEnvSecurity.DENYLIST plus the AWS key id
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN",
            "SSH_PRIVATE_KEY",
            "GOOGLE_APPLICATION_CREDENTIALS",
        )

    val CI_DETECTION: List<String> =
        listOf(
            "CI",
            "CI_SERVER",
            // GitLab
            "GITLAB_CI",
            "CI_PROJECT_ID",
            "CI_PROJECT_PATH",
            "CI_MERGE_REQUEST_IID",
            "CI_SERVER_URL",
            "CI_API_V4_URL",
            // GitHub
            "GITHUB_ACTIONS",
            "GITHUB_REPOSITORY",
            "GITHUB_REF",
            "GITHUB_EVENT_PATH",
            // Bitbucket
            "BITBUCKET_BUILD_NUMBER",
            "BITBUCKET_REPO_FULL_NAME",
            "BITBUCKET_PR_ID",
            // Azure DevOps
            "TF_BUILD",
            "SYSTEM_TEAMFOUNDATIONSERVERURI",
            "AZURE_HTTP_USER_AGENT",
            // Jenkins, Gerrit, Gitea, AWS CodeBuild
            "JENKINS_URL",
            "CHANGE_URL",
            "GERRIT_URL",
            "GERRIT_PROJECT",
            "GERRIT_CHANGE_NUMBER",
            "GITEA_SERVER_URL",
            "GITEA_PR_ID",
            "CODEBUILD_BUILD_ID",
        )
}
