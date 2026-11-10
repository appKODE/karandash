package io.karandash.loader.bridge

import io.karandash.api.exception.ContentResolveException
import io.karandash.platform.safeParent
import io.karandash.sdk.content.ContentSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * The one content rule for every guest transport: inline values, [ContentSource.Multiple] of allowed sources,
 * local files under the config directory or the git checkout that holds it (symlinks resolved first, so a link
 * pointing outside is refused; at most [MAX_FILE_BYTES]), and repo files through the host's [repoResolver] when
 * the transport has one. Remote files are refused on every guest: a guest gets no network egress through the host.
 */
internal class GuestContentPolicy(
    configDir: String,
    private val repoResolver: (suspend (ContentSource.RepoFile) -> String)? = null,
) {
    private val roots: List<Path> = canonical(Path(configDir))?.let { listOfNotNull(it, gitTopLevel(it)).distinct() }.orEmpty()

    suspend fun resolve(source: ContentSource): String =
        when (source) {
            is ContentSource.Inline -> source.value
            is ContentSource.Multiple -> source.sources.map { resolve(it) }.joinToString(source.separator)
            is ContentSource.LocalFile -> readLocal(source.path)
            is ContentSource.RepoFile -> repoResolver?.invoke(source) ?: refuse(source, "no repository access on this transport")
            is ContentSource.RemoteFile -> refuse(source, "guests get no network egress; inline the content or use a local file")
        }

    private fun readLocal(raw: String): String {
        val base = roots.firstOrNull() ?: throw ContentResolveException(LOCAL_FILE, raw, "Config directory is not readable.")
        if (Path(
                raw
            ).isAbsolute
        ) {
            throw ContentResolveException(LOCAL_FILE, raw, "Local file '$raw' must be relative to the config directory.")
        }
        val file = canonical(Path(base, raw)) ?: throw ContentResolveException(LOCAL_FILE, raw, "Local file not found: $raw")
        if (roots.none { file.isUnder(it) }) {
            throw ContentResolveException(LOCAL_FILE, raw, "Local file '$raw' is outside the config directory and its git checkout.")
        }
        val meta = SystemFileSystem.metadataOrNull(file)
        if (meta?.isRegularFile != true) throw ContentResolveException(LOCAL_FILE, raw, "Local file is not a regular file: $raw")
        if (meta.size > MAX_FILE_BYTES) throw ContentResolveException(LOCAL_FILE, raw, "Local file '$raw' exceeds $MAX_FILE_BYTES bytes.")
        return SystemFileSystem
            .source(file)
            .buffered()
            .use { it.readByteArray() }
            .decodeToString()
    }

    private fun refuse(
        source: ContentSource,
        reason: String,
    ): Nothing =
        throw ContentResolveException(
            sourceType = source::class.simpleName ?: "ContentSource",
            reference = source.toString(),
            message = "${source::class.simpleName} is not available to plugin guests: $reason.",
        )

    companion object {
        /** Largest local file a guest may read. */
        const val MAX_FILE_BYTES: Long = 1024L * 1024
        private const val LOCAL_FILE = "LocalFile"

        /** Absolute path with every symlink resolved, or `null` when it does not exist. */
        private fun canonical(path: Path): Path? = if (SystemFileSystem.exists(path)) SystemFileSystem.resolve(path) else null

        /** The nearest ancestor of [dir] (itself included) holding a `.git` entry, or `null`. */
        private fun gitTopLevel(dir: Path): Path? =
            generateSequence(dir) { it.safeParent }.firstOrNull { SystemFileSystem.exists(Path(it, ".git")) }

        private fun Path.isUnder(root: Path): Boolean {
            val r = root.toString().trimEnd('/', '\\')
            val p = toString()
            return p == r || p.startsWith("$r/") || p.startsWith("$r\\")
        }
    }
}
