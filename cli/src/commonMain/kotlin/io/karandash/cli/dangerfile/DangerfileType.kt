package io.karandash.cli.dangerfile

import kotlinx.serialization.Serializable

/** Supported Dangerfile languages. */
enum class DangerfileType { JS, KTS }

/** A detected Dangerfile with its resolved absolute path and language type. */
@Serializable
data class DetectedDangerfile(
    val absolutePath: String,
    val type: DangerfileType
)
