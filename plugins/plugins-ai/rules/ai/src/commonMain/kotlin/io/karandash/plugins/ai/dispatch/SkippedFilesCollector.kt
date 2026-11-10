package io.karandash.plugins.ai.dispatch

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable

/**
 * Paths the AI preprocessing left out of at least one rule's diff this run, by reason.
 *
 * @param tooLarge Dropped by `ai.partialReview` because the MR has more files than `maxFiles`.
 * @param filtered Dropped by the include/exclude file filters or the exclude policy.
 * @param binary Dropped as binary diffs.
 */
@Serializable
internal data class SkippedFiles(
    val tooLarge: Set<String> = emptySet(),
    val filtered: Set<String> = emptySet(),
    val binary: Set<String> = emptySet(),
)

/**
 * Collects [SkippedFiles] across rules; rules preprocess concurrently, so paths are unioned atomically.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class SkippedFilesCollector {
    private val skipped = atomic(SkippedFiles())

    fun add(
        tooLarge: Collection<String> = emptyList(),
        filtered: Collection<String> = emptyList(),
        binary: Collection<String> = emptyList(),
    ) {
        if (tooLarge.isEmpty() && filtered.isEmpty() && binary.isEmpty()) return
        skipped.update {
            SkippedFiles(it.tooLarge + tooLarge, it.filtered + filtered, it.binary + binary)
        }
    }

    fun snapshot(): SkippedFiles = skipped.value
}
