package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi

/**
 * Reads a repository file at a ref through the run's platform service (`PlatformServiceModule.fetchFileContent`).
 * Provided as [RunServiceKeys.REPOSITORY_FILE] only when the run has a live platform and project.
 */
@ExperimentalKarandashApi
public fun interface RepositoryFileReader {
    /** Content of [path] at [ref] (a branch or sha), or `null` when it does not exist or the platform cannot fetch it. */
    public suspend fun read(
        ref: String,
        path: String,
    ): String?
}
