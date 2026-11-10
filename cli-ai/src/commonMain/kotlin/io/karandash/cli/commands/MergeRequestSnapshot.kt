package io.karandash.cli.commands

import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.platform.readText
import kotlinx.io.files.Path

/** A [MergeRequest] saved as JSON (`validate --mr`, `test-rule --snapshot`); unknown keys are ignored. */
object MergeRequestSnapshot {
    fun parse(json: String): MergeRequest = KarandashJson.Default.decodeFromString(MergeRequest.serializer(), json)

    fun read(path: String): MergeRequest = parse(Path(path).readText())
}
