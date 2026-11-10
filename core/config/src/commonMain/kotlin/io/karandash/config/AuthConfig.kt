package io.karandash.config

import kotlinx.serialization.Serializable

/**
 * Named authentication configuration for content resolution.
 * Referenced by name in [io.karandash.sdk.content.ContentSource.RemoteFile] and
 * [io.karandash.sdk.content.ContentSource.LocalFile].
 */
@Serializable
data class AuthConfig(
    val type: AuthType,
    val token: String? = null,
    val name: String? = null,
    val value: String? = null,
    val username: String? = null,
    val password: String? = null,
    /** Host (and port) this credential is keyed for: a content source on another host is refused. Unset: any host. */
    val baseUrl: String? = null,
    /**
     * Default `file-resolution` (`local`, `fallback-remote`, `fallback-local`, `strict`) of every `extends:` entry
     * that names this auth (`auth: <name>`) and sets none of its own. Unset: `local`.
     */
    val fileResolution: String? = null,
)
