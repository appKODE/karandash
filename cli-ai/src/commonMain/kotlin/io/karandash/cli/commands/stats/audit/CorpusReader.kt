package io.karandash.cli.commands.stats.audit

import io.karandash.models.support.KarandashJson
import io.karandash.platform.readText
import io.karandash.platform.safeName
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** Reads a frozen corpus dir (`<dir>/<project>/<iid>.json`) in a stable order: project dir, then iid. */
object CorpusReader {
    private const val MANIFEST = "manifest.json"

    fun read(dir: Path): List<CorpusMr> {
        require(SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) { "Corpus directory not found: $dir" }
        return SystemFileSystem
            .list(dir)
            .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
            .sortedBy { it.safeName }
            .flatMap { projectDir ->
                SystemFileSystem
                    .list(projectDir)
                    .filter { it.safeName.endsWith(".json") && it.safeName != MANIFEST }
                    .map { parse(it.readText()) }
                    .sortedBy { it.iid }
            }
    }

    fun parse(text: String): CorpusMr = KarandashJson.Default.decodeFromString(CorpusMr.serializer(), text)
}
