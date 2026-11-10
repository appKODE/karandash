plugins {
    id("karandash.kmp-distributable-plugin")
    id("karandash.kmp-ktor-library")
}

/**
 * Convention plugin for built-in plugin modules that use Ktor (HTTP providers,
 * reporters with webhooks) and should be distributable as standalone native
 * shared libraries (.dylib/.so/.dll).
 *
 * [karandash.kmp-distributable-plugin] (sharedLib binary per native target) plus
 * [karandash.kmp-ktor-library] (Ktor engine dependencies).
 *
 * Apply this convention instead of `karandash.kmp-ktor-library` for HTTP-using
 * modules that should support standalone distribution.
 */
