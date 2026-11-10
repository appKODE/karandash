package io.karandash.manifest

import io.karandash.http.HttpAuth

/**
 * Source location for a config file, with optional authentication and caching.
 * Used for config inheritance via `extends:` field.
 *
 * @property url The URL or file path to fetch.
 * @property auth Optional authentication credentials for remote fetches.
 * @property cachePolicy Optional cache policy for remote fetches.
 * @property emptyAuthVars Env vars named by an [auth] value that interpolated to blank (unset or `""`);
 *   a failed fetch names them so a missing token is not mistaken for a missing file.
 * @property trusted `trust: true` on the `extends:` entry: a remote layer's `{ file: }` values may then read
 *   local files outside the project root (absolute paths, `..` past the root).
 *
 * Not @Serializable: runtime context holder carrying a live credential ([auth]) that must never be written out.
 */
data class ConfigSource(
    val url: String,
    val auth: HttpAuth? = null,
    val cachePolicy: CachePolicy? = null,
    val emptyAuthVars: List<String> = emptyList(),
    val trusted: Boolean = false,
)
