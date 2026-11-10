package io.karandash.api.finding

import io.karandash.models.finding.Location
import kotlinx.serialization.Serializable

/**
 * Flat representation of a [Location] for reporters that need a simple file + line pair.
 *
 * @property file The file path, or `"commit:<sha>"` for commit references.
 * @property line The line number within the file, or null.
 */
@Serializable
public data class FlatLocation(
    val file: String?,
    val line: Int?
)

/**
 * Converts a [Location] to a [FlatLocation], collapsing [Location.FileRef] and
 * [Location.CommitRef] into a single flat structure.
 */
public fun Location.toFlatLocation(): FlatLocation =
    when (this) {
        is Location.FileRef -> FlatLocation(file = path, line = line)
        is Location.CommitRef -> FlatLocation(file = "commit:$sha", line = null)
    }
