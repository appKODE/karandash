package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.ContentResolveException
import io.karandash.api.support.rethrowCancellation
import io.karandash.content.LocalContentReader
import io.karandash.http.HttpAuth
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.RemoteFileResolution
import io.karandash.platform.PathSecurity
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.exists
import io.karandash.platform.isAbsolutePath
import io.karandash.platform.resolveUserPath
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Context for resolving `{ file: "..." }` references - either locally or against a remote URL.
 */
sealed class FileResolutionContext {
    /**
     * Resolve file references against a local base directory.
     *
     * @property onMissingFile When set, a missing `{ file: }` is reported here (reference path, resolved
     *   path) and resolves to `""` instead of failing: a shared base config validated on its own
     *   (`config validate --base`) names files that only its consumers provide.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `Path` and a callback.
     */
    data class Local(
        val basePath: Path,
        val onMissingFile: ((reference: String, resolved: Path) -> Unit)? = null,
    ) : FileResolutionContext()

    /**
     * Resolve file references against a remote base URL.
     *
     * @property baseUrl The base URL directory (without trailing slash) to resolve relative paths against.
     *   Ignored when [parsedApiUrl] is set - API-aware resolution is used instead.
     * @property auth Optional authentication inherited from the parent `extends:` entry.
     * @property fetcher Lambda that fetches content from a URL with optional auth.
     * @property localBasePath Local working directory for `{ file-local: "..." }` resolution and
     *   fallback. Required for `file-local`; used for `file` fallback depending on [resolution] mode.
     * @property resolution Controls how `{ file: "..." }` references are resolved. Does not affect
     *   `file-local` (always local) or `file-remote` (always remote, never falls back).
     * @property onWarning Callback invoked when a fallback occurs, to emit a warning message.
     * @property parsedApiUrl When the config was fetched from a Git platform API URL (GitLab, GitHub),
     *   this carries the parsed URL for API-aware file reference resolution. When set, file references
     *   are resolved within the repo file tree and reconstructed as valid API URLs.
     * @property localRoot Local reads (`file-local`, and `file` in the local modes) must stay under this directory;
     *   null bounds them by [localBasePath]. Absolute paths are rejected.
     * @property trusted Set by `trust: true` on the `extends:` entry: lifts the [localRoot] bound.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `ParsedApiUrl?`.
     */
    data class Remote(
        val baseUrl: String,
        val auth: HttpAuth?,
        val fetcher: suspend (url: String, auth: HttpAuth?) -> String,
        val localBasePath: Path? = null,
        val resolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
        val onWarning: ((String) -> Unit)? = null,
        val parsedApiUrl: ParsedApiUrl? = null,
        val localRoot: Path? = null,
        val trusted: Boolean = false,
    ) : FileResolutionContext()
}

/**
 * Resolves `{ file: "..." }` external value references in config [JsonElement] trees.
 *
 * Local reads go through [LocalContentReader] - the same I/O path (and 1 MB cap) that
 * `ContentResolver` uses for plugin `ContentSource.LocalFile`. Remote reads use [HttpAuth] +
 * [RemoteConfigFetcher], which `ContentResolver` also uses via `asContentFetcher()`.
 *
 * When the resolver encounters a [JsonObject] with a single `file` key, it reads the
 * referenced file and replaces the object with a [JsonPrimitive] containing the file content.
 *
 * Security: objects under keys in [SECURITY_DENYLIST] are never resolved to prevent
 * accidental exfiltration of sensitive file content into auth/token fields.
 */
object ExternalValueResolver {
    private const val FILE_KEY = "file"
    private const val FILE_LOCAL_KEY = "file-local"
    private const val FILE_REMOTE_KEY = "file-remote"
    private val FILE_REF_KEYS = setOf(FILE_KEY, FILE_LOCAL_KEY, FILE_REMOTE_KEY)
    private val ERROR_PREFIX = "${KarandashError.EXTERNAL_VALUE_RESOLVE_FAILED.bracketCode} "

    /**
     * Parent key names under which external value resolution is skipped.
     * Prevents reading arbitrary files into security-sensitive config fields.
     */
    private val SECURITY_DENYLIST = setOf(
        "auth",
        "token",
        "password",
        "secret",
        "credentials",
        "private-key",
        "privateKey",
    )

    private val CAMEL_BOUNDARY = Regex("([a-z0-9])(?=[A-Z])")

    /**
     * Recursively resolves `{ file: "..." }` references in the given [element].
     *
     * @param element The config tree to process.
     * @param basePath The base directory for resolving relative file paths.
     * @param logger Logger for debug output during resolution.
     * @return A new [JsonElement] tree with file references replaced by their content.
     * @throws ConfigException if a referenced file cannot be read.
     */
    fun resolve(
        element: JsonElement,
        basePath: Path = currentWorkingDirectory(),
        logger: Logger = NoOpLogger,
    ): JsonElement = resolveElement(element, FileResolutionContext.Local(basePath), parentKey = null, logger = logger)

    private fun resolveElement(
        element: JsonElement,
        context: FileResolutionContext.Local,
        parentKey: String?,
        logger: Logger = NoOpLogger,
    ): JsonElement =
        when (element) {
            is JsonObject -> resolveObject(element, context, parentKey, logger)
            is JsonArray -> JsonArray(element.map { resolveElement(it, context, parentKey, logger) })
            else -> element
        }

    private fun resolveObject(
        obj: JsonObject,
        context: FileResolutionContext.Local,
        parentKey: String?,
        logger: Logger = NoOpLogger,
    ): JsonElement {
        val basePath = context.basePath
        if (isFileReference(obj)) {
            if (isDeniedParent(parentKey)) {
                logger.warn(deniedMessage(parentKey))
                return obj
            }
            val refKey = fileRefKey(obj)
            val refPath = (obj[refKey ?: FILE_KEY] as? JsonPrimitive)?.content ?: ""
            logger.debug("ExternalValueResolver: resolving { $refKey: \"$refPath\" } relative to $basePath")
            val result = resolveFileReference(obj, context)
            logger.debug("ExternalValueResolver: successfully resolved file reference '$refPath'")
            return result
        }

        return JsonObject(
            obj.entries.associate { (key, value) ->
                key to resolveElement(value, context, parentKey = childKey(parentKey, key), logger = logger)
            }
        )
    }

    /** True when [element] holds a `{ file: }` reference that [resolve] would read (not one under a denied key). */
    internal fun hasFileReference(
        element: JsonElement,
        parentKey: String? = null,
    ): Boolean =
        when (element) {
            is JsonObject -> {
                (isFileReference(element) && !isDeniedParent(parentKey)) ||
                    element.entries.any { (key, value) -> hasFileReference(value, childKey(parentKey, key)) }
            }

            is JsonArray -> {
                element.any { hasFileReference(it, parentKey) }
            }

            else -> {
                false
            }
        }

    /**
     * [element] with every `{ file: }` reference still in it (one under a credential key is left unresolved, with its
     * own warning) replaced by null, so schema checks do not warn a second time about an object where a string belongs.
     */
    fun withoutFileReferences(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> if (isFileReference(element)) JsonNull else JsonObject(element.mapValues { withoutFileReferences(it.value) })
            is JsonArray -> JsonArray(element.map { withoutFileReferences(it) })
            else -> element
        }

    private fun isFileReference(obj: JsonObject): Boolean =
        obj.size == 1 && FILE_REF_KEYS.any { key -> obj.containsKey(key) && obj[key] is JsonPrimitive }

    /** Returns the file reference key used (`file`, `file-local`, or `file-remote`), or null. */
    private fun fileRefKey(obj: JsonObject): String? =
        FILE_REF_KEYS.firstOrNull { key -> obj.containsKey(key) && obj[key] is JsonPrimitive }

    /**
     * A credential-like parent key: one of [SECURITY_DENYLIST], or a name [KarandashEnvSecurity.isSensitive] flags
     * once camelCase and kebab-case are spelled as `_`-separated words (`apiKey`, `gitlab-token`, `clientSecret`).
     */
    private fun isDeniedParent(parentKey: String?): Boolean =
        parentKey != null &&
            (
                SECURITY_DENYLIST.any { it.equals(parentKey, ignoreCase = true) } ||
                    KarandashEnvSecurity.isSensitive("_" + parentKey.replace(CAMEL_BOUNDARY, "$1_").replace('-', '_'))
            )

    /** The key a child of [key] is checked against: a credential-like ancestor stays in force for the whole subtree. */
    private fun childKey(
        parentKey: String?,
        key: String,
    ): String = if (isDeniedParent(parentKey)) checkNotNull(parentKey) else key

    private fun deniedMessage(parentKey: String?): String =
        "{ file: } under '$parentKey' is not read: a credential-like key never takes file content; " +
            "set the value directly or via \${ENV_VAR}"

    private fun resolveFileReference(
        obj: JsonObject,
        context: FileResolutionContext.Local,
    ): JsonPrimitive {
        val key = fileRefKey(obj) ?: FILE_KEY
        val filePath = (obj[key] as? JsonPrimitive)?.content
            ?: throw ConfigException(
                "${ERROR_PREFIX}Invalid file reference: 'file' value must be a string"
            )

        if (filePath.isBlank()) {
            throw ConfigException(
                "${ERROR_PREFIX}Invalid file reference: 'file' path is empty"
            )
        }

        val resolved = resolveUserPath(filePath, context.basePath)

        if (!resolved.exists()) {
            context.onMissingFile?.let { report ->
                report(filePath, resolved)
                return JsonPrimitive("")
            }
            throw ConfigException(
                "${ERROR_PREFIX}External value file not found: $filePath",
                cause = null,
                hint = "Create '$filePath' or fix the '{ file: ... }' path; it resolves from the config file's directory.",
            )
        }

        return JsonPrimitive(readLocal(resolved, filePath))
    }

    // ── Remote-aware resolution ─────────────────────────────────────────

    /**
     * Resolves `{ file: "..." }` references using a [FileResolutionContext].
     *
     * For [FileResolutionContext.Local], behaves identically to [resolve].
     * For [FileResolutionContext.Remote], fetches file content from the remote server
     * using the same auth as the parent `extends:` entry.
     *
     * @param element The config tree to process.
     * @param context Resolution context (local path or remote URL + auth).
     * @return A new [JsonElement] tree with file references replaced by their content.
     * @throws ConfigException if a referenced file cannot be read or security checks fail.
     */
    suspend fun resolve(
        element: JsonElement,
        context: FileResolutionContext,
        logger: Logger = NoOpLogger,
    ): JsonElement =
        when (context) {
            is FileResolutionContext.Local -> resolveElement(element, context, parentKey = null, logger = logger)
            is FileResolutionContext.Remote -> resolveRemoteElement(element, context, parentKey = null, logger = logger)
        }

    private suspend fun resolveRemoteElement(
        element: JsonElement,
        context: FileResolutionContext.Remote,
        parentKey: String?,
        logger: Logger,
    ): JsonElement =
        when (element) {
            is JsonObject -> resolveRemoteObject(element, context, parentKey, logger)
            is JsonArray -> JsonArray(element.map { resolveRemoteElement(it, context, parentKey, logger) })
            else -> element
        }

    private suspend fun resolveRemoteObject(
        obj: JsonObject,
        context: FileResolutionContext.Remote,
        parentKey: String?,
        logger: Logger,
    ): JsonElement {
        if (isFileReference(obj) && isDeniedParent(parentKey)) {
            logger.warn(deniedMessage(parentKey))
            return obj
        }
        if (isFileReference(obj)) {
            val key = fileRefKey(obj)
            return when (key) {
                FILE_LOCAL_KEY -> resolveLocalFileRef(obj, key, context)

                FILE_REMOTE_KEY -> resolveRemoteFileReference(obj, context, forceNoFallback = true)

                else -> when (context.resolution) {
                    RemoteFileResolution.LOCAL -> resolveLocalFileRef(obj, FILE_KEY, context)
                    RemoteFileResolution.FALLBACK_REMOTE -> resolveLocalWithRemoteFallback(obj, context, logger)
                    RemoteFileResolution.FALLBACK_LOCAL -> resolveRemoteFileReference(obj, context, forceNoFallback = false)
                    RemoteFileResolution.STRICT -> resolveRemoteFileReference(obj, context, forceNoFallback = true)
                }
            }
        }

        return JsonObject(
            obj.entries.associate { (key, value) ->
                key to resolveRemoteElement(value, context, parentKey = childKey(parentKey, key), logger = logger)
            }
        )
    }

    /**
     * Resolves a `{ file-local: "..." }` reference against the local working directory,
     * even when processing a remote config.
     */
    private fun resolveLocalFileRef(
        obj: JsonObject,
        key: String,
        context: FileResolutionContext.Remote,
    ): JsonPrimitive {
        val localPath = context.localBasePath
            ?: throw ConfigException(
                "${ERROR_PREFIX}'$key' references require a local working directory, " +
                    "but local fallback is not available in this context"
            )
        val filePath = (obj[key] as? JsonPrimitive)?.content
            ?: throw ConfigException("${ERROR_PREFIX}Invalid file reference: '$key' value must be a string")

        if (filePath.isBlank()) {
            throw ConfigException("${ERROR_PREFIX}Invalid file reference: '$key' path is empty")
        }

        val resolved = boundedLocalPath(filePath, localPath, context)
        if (!resolved.exists()) {
            throw ConfigException(
                "${ERROR_PREFIX}Local file not found: $filePath (resolved to $resolved, base: $localPath)"
            )
        }

        return JsonPrimitive(readLocal(resolved, filePath))
    }

    /**
     * Resolves a `{ file: "..." }` reference locally first; if the local file is not found,
     * falls back to fetching from the remote server. That fallback is logged at debug, not warned:
     * this mode is only reached when `file-resolution: fallback-remote` is set (the default is `local`),
     * so reading the remote copy is what the user asked for.
     * Fails if both local and remote resolution fail.
     */
    private suspend fun resolveLocalWithRemoteFallback(
        obj: JsonObject,
        context: FileResolutionContext.Remote,
        logger: Logger,
    ): JsonPrimitive {
        val filePath = (obj[FILE_KEY] as? JsonPrimitive)?.content
            ?: throw ConfigException("${ERROR_PREFIX}Invalid file reference: 'file' value must be a string")

        if (filePath.isBlank()) {
            throw ConfigException("${ERROR_PREFIX}Invalid file reference: 'file' path is empty")
        }

        val localPath = context.localBasePath
        if (localPath != null) {
            val localResolved = boundedLocalPath(filePath, localPath, context)
            if (localResolved.exists()) {
                return JsonPrimitive(readLocal(localResolved, filePath))
            }
        }

        val resolvedUrl = resolveRemoteUrlForContext(filePath, context)

        return try {
            val content = context.fetcher(resolvedUrl, context.auth)
            logger.debug("Local file '$filePath' not found (checked: $localPath). Falling back to remote: $resolvedUrl")
            JsonPrimitive(content.trimEnd())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: injected fetcher (HTTP or platform) can fail in any way
            e.rethrowCancellation()
            throw ConfigException(
                "${ERROR_PREFIX}File reference '$filePath' not found locally" +
                    " (checked: ${context.localBasePath})" +
                    " and remote fetch from $resolvedUrl also failed: ${e.message}"
            )
        }
    }

    private suspend fun resolveRemoteFileReference(
        obj: JsonObject,
        context: FileResolutionContext.Remote,
        forceNoFallback: Boolean,
    ): JsonPrimitive {
        val key = fileRefKey(obj) ?: FILE_KEY
        val filePath = (obj[key] as? JsonPrimitive)?.content
            ?: throw ConfigException(
                "${ERROR_PREFIX}Invalid file reference: '$key' value must be a string"
            )

        if (filePath.isBlank()) {
            throw ConfigException(
                "${ERROR_PREFIX}Invalid file reference: '$key' path is empty"
            )
        }

        val resolvedUrl = resolveRemoteUrlForContext(filePath, context)

        return try {
            val content = context.fetcher(resolvedUrl, context.auth)
            JsonPrimitive(content.trimEnd())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: injected fetcher (HTTP or platform) can fail in any way
            e.rethrowCancellation()
            if (!forceNoFallback) {
                tryLocalFallback(filePath, resolvedUrl, context, e)
            } else {
                null
            } ?: throw if (e is ConfigException) {
                e
            } else {
                ConfigException(
                    "${ERROR_PREFIX}Failed to fetch remote file reference $filePath from $resolvedUrl: ${e.message}"
                )
            }
        }
    }

    /**
     * Attempts local file resolution as a fallback when remote fetch fails.
     *
     * @return Resolved [JsonPrimitive] if local fallback succeeds, or null if fallback is disabled
     *   or the local file does not exist.
     */
    private fun tryLocalFallback(
        filePath: String,
        resolvedUrl: String,
        context: FileResolutionContext.Remote,
        remoteError: Exception,
    ): JsonPrimitive? {
        if (context.resolution != RemoteFileResolution.FALLBACK_LOCAL) return null
        val fallbackPath = context.localBasePath ?: return null

        val localResolved = boundedLocalPath(filePath, fallbackPath, context)
        if (!localResolved.exists()) return null

        return try {
            val content = readLocal(localResolved, filePath)
            context.onWarning?.invoke(
                "Remote file reference '$filePath' not found at $resolvedUrl " +
                    "(${remoteError.message}). Falling back to local file: $localResolved (base: $fallbackPath)"
            )
            JsonPrimitive(content)
        } catch (e: ConfigException) {
            context.onWarning?.invoke("Local fallback for '$filePath' failed: ${e.message}")
            null
        }
    }

    /**
     * [filePath] from a remote layer resolved against [base]; unless the layer is [FileResolutionContext.Remote.trusted],
     * an absolute path or one that normalizes outside [FileResolutionContext.Remote.localRoot] is rejected.
     */
    private fun boundedLocalPath(
        filePath: String,
        base: Path,
        context: FileResolutionContext.Remote,
    ): Path {
        val resolved = resolveUserPath(filePath, base)
        if (context.trusted) return resolved
        val root = normalized(context.localRoot ?: base)
        val candidate = normalized(resolved)
        if (!isAbsolutePath(filePath) && (candidate == root || candidate.startsWith("$root/"))) return resolved
        throw ConfigException(
            "$ERROR_PREFIX'$filePath' in a remote config resolves outside the project root ($root)",
            cause = null,
            hint = "Use a path under the project root, or set 'trust: true' on the extends entry if you trust that config.",
        )
    }

    private fun normalized(path: Path): String = PathSecurity.normalizePath(path.toString().replace('\\', '/'))

    /** Reads a local file through the shared [LocalContentReader] path, trimming trailing whitespace. */
    private fun readLocal(
        path: Path,
        reference: String,
    ): String =
        try {
            LocalContentReader.read(path, reference).trimEnd()
        } catch (e: ContentResolveException) {
            throw ConfigException("$ERROR_PREFIX${e.message}", e)
        }

    /**
     * Resolves a file reference URL using API-aware resolution when available,
     * falling back to [PathSecurity.resolveRemoteUrl] for non-API URLs.
     *
     * For API URLs (GitLab, GitHub), [ParsedApiUrl.resolve] produces a valid API URL
     * with proper encoding, `/raw` suffix, and query params. Path traversal is inherently
     * bounded by repo-relative resolution - `..` segments cannot escape above the repo root.
     *
     * For non-API URLs, delegates to [PathSecurity] for validation and resolution.
     */
    private fun resolveRemoteUrlForContext(
        filePath: String,
        context: FileResolutionContext.Remote,
    ): String {
        val parsed = context.parsedApiUrl
        if (parsed != null) return parsed.resolve(filePath)
        validateRemoteFilePath(filePath, context.baseUrl)
        return resolveRemoteUrl(context.baseUrl, filePath)
    }

    /**
     * Validates that a remote file path is safe: must be relative, no absolute URLs,
     * and after normalization must not escape above the config's directory.
     */
    fun validateRemoteFilePath(
        filePath: String,
        baseUrl: String,
    ) {
        try {
            PathSecurity.validateRemoteFilePath(filePath, baseUrl)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("$ERROR_PREFIX${e.message}")
        }
    }

    fun resolveRemoteUrl(
        baseUrl: String,
        relativePath: String,
    ): String = PathSecurity.resolveRemoteUrl(baseUrl, relativePath)
}
