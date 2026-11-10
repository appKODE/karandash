package io.karandash.api.platform

/**
 * Implemented by a platform service that can report the current build's CI status from the CI system itself
 * (for example Jenkins), for merge requests whose hosting platform returned no CI status.
 *
 * The host asks every registered implementation after a merge request is fetched and fills
 * `MergeRequest.ciStatus` and `ciJobs` from the first non-null answer.
 */
public fun interface CiStatusSource {
    /** `success`, `failed` or `pending` for the build described by [env], or null outside this CI or when unreadable. */
    public suspend fun fetchCiStatus(env: (String) -> String?): String?
}
