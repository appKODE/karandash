package io.karandash.cli

/**
 * Env for tests that run `--pr` without a token: platform API URLs point at a `.invalid` host, so the
 * fetch fails offline instead of reaching the public platform.
 */
internal val OFFLINE_ENV: Map<String, String> = mapOf(
    "GITLAB_URL" to "https://gitlab.invalid",
    "GITHUB_API_URL" to "https://github.invalid",
)
