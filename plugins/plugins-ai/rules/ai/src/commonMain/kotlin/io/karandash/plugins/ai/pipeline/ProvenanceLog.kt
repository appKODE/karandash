package io.karandash.plugins.ai.pipeline

import io.karandash.api.finding.FindingHashChain
import io.karandash.crypto.sha256Hex
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * `ai.architecture.provenanceChain.persistChain`: a JSON Lines file with one entry per run. Each entry's `chainHash`
 * hashes the previous entry's `chainHash` with this run's findings chain hash, so editing or dropping a line breaks
 * every later hash.
 */
internal object ProvenanceLog {
    fun append(
        output: String,
        mrId: String,
        result: FindingHashChain,
        logger: Logger,
    ) {
        try {
            val path = Path(output)
            val previousHash = lastChainHash(path)
            val entry = buildJsonObject {
                put("timestamp", Clock.System.now().toString())
                put("mrId", mrId)
                put("findingCount", result.findingCount)
                put("configHash", result.configHash)
                put("mrSnapshotHash", result.mrSnapshotHash)
                put("findingsHash", result.chainHash)
                put("previousHash", previousHash)
                put("chainHash", sha256Hex("$previousHash|${result.chainHash}"))
            }
            path.safeParent?.let { createDirectories(it) }
            SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("$entry\n") }
        } catch (e: IOException) {
            logger.warn("Could not append the provenance chain to $output: ${e.message}")
        }
    }

    /** `chainHash` of the file's last entry; empty for a missing or empty file; a last line that is not JSON fails the append. */
    private fun lastChainHash(path: Path): String {
        if (!SystemFileSystem.exists(path)) return ""
        val last = SystemFileSystem
            .source(path)
            .buffered()
            .use { it.readString() }
            .lineSequence()
            .lastOrNull { it.isNotBlank() }
        return try {
            last
                ?.let {
                    (
                        KarandashJson.Default.parseToJsonElement(
                            it
                        ) as? JsonObject
                    )?.get("chainHash") as? JsonPrimitive
                }?.contentOrNull
                .orEmpty()
        } catch (e: SerializationException) {
            throw IOException("last line is not JSON: ${e.message}", e)
        }
    }
}
