package io.karandash.models.finding

import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A suggested replacement of lines [startLine]..[endLine] (1-based, inclusive) of [path] by [suggestedCode].
 *
 * Read from a finding by [codeChange]; every format renders it in its own way (SARIF `fixes`, reviewdog
 * `suggestions`, a markdown suggestion block, JSON `codeChange`). Line numbers are the producer's hint: a
 * consumer that applies the change should check that [existingCode] is still there.
 *
 * @property existingCode The code being replaced, when the producer gave it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeChange(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val suggestedCode: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val existingCode: String? = null,
)

/**
 * The code change this finding suggests: its [WellKnownMetadataKeys.SUGGESTED_CODE] for the lines of its
 * [Location.FileRef]. `null` without a file location with a line, or without non-blank suggested code. The end line
 * is the location's `endLine`, else the span of [WellKnownMetadataKeys.EXISTING_CODE], else the start line.
 */
fun Finding.codeChange(): CodeChange? {
    val ref = location as? Location.FileRef ?: return null
    val start = ref.line ?: return null
    val suggested = metadata[WellKnownMetadataKeys.SUGGESTED_CODE]?.takeIf { it.isNotBlank() } ?: return null
    val existing = metadata[WellKnownMetadataKeys.EXISTING_CODE]?.takeIf { it.isNotBlank() }
    val end = ref.endLine ?: existing?.let { start + it.trimEnd('\n').lines().size - 1 } ?: start
    return CodeChange(ref.path, start, maxOf(start, end), suggested, existing)
}
