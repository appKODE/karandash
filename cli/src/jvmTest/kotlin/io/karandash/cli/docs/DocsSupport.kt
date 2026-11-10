package io.karandash.cli.docs

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path

/** Repo-file access for the docs sync tests (Gradle may run them from the module or the root directory). */
internal object DocsSupport {
    private val root: Path = checkNotNull(TestFiles.findRepoRoot()) { "repo root (settings.gradle.kts) not found" }

    fun path(relative: String): Path = Path(root, relative)

    fun read(relative: String): String = TestFiles.readText(path(relative))

    fun readOrEmpty(relative: String): String = if (TestFiles.exists(path(relative))) read(relative) else ""

    /** Bodies of the fenced blocks with the given info string (`yaml`, `bash`, ...), in document order. */
    fun fencedBlocks(
        markdown: String,
        lang: String,
    ): List<String> = Regex("```$lang\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(markdown).map { it.groupValues[1] }.toList()
}
