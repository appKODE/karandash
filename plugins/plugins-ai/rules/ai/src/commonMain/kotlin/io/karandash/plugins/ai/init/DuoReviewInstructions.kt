package io.karandash.plugins.ai.init

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.ReviewInstruction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses GitLab Duo's `.gitlab/duo/mr-review-instructions.yaml` (`instructions: [{name, fileFilters, instructions}]`)
 * into [ReviewInstruction]s, for `ai.review.duoInstructionsFile: true`. The pipeline reads the file from the MR base
 * (the target), never the head, so an MR cannot rewrite the instructions its own review gets.
 */
internal object DuoReviewInstructions {
    const val PATH: String = ".gitlab/duo/mr-review-instructions.yaml"

    /** Largest file read; a bigger one is ignored whole. */
    const val MAX_BYTES: Int = 16 * 1024

    /** Most entries read; the rest are dropped. */
    const val MAX_ENTRIES: Int = 20

    /**
     * [text] is the file at the MR base, or null when it is absent there (or the platform cannot read files).
     * No YAML parser yields nothing; an absent file is logged at debug (most repositories have none); an oversized
     * or unparsable file, a bad entry and entries past [MAX_ENTRIES] are warned about and left out.
     */
    suspend fun parse(
        text: String?,
        parser: ConfigFormatModule?,
        logger: Logger,
    ): List<ReviewInstruction> {
        if (parser == null) return emptyList()
        if (text == null) {
            logger.debug("$PATH not found on the target branch (or the platform cannot read files); ignored")
            return emptyList()
        }
        if (text.encodeToByteArray().size > MAX_BYTES) {
            logger.warn("$PATH is larger than $MAX_BYTES bytes (16 KB); ignored")
            return emptyList()
        }
        val root =
            try {
                parser.parseToJsonElement(text) as? JsonObject
            } catch (e: IllegalArgumentException) {
                logger.warn("$PATH is not valid YAML, its review instructions are ignored: ${e.message}")
                return emptyList()
            } catch (e: ConfigParseException) {
                logger.warn("$PATH is not valid YAML, its review instructions are ignored: ${e.message}")
                return emptyList()
            }
        val entries = root?.get("instructions") as? JsonArray ?: return emptyList()
        if (entries.size > MAX_ENTRIES) {
            logger.warn("$PATH has ${entries.size} instructions; the last ${entries.size - MAX_ENTRIES} past $MAX_ENTRIES are ignored")
        }
        return entries.take(MAX_ENTRIES).mapNotNull { toInstruction(it as? JsonObject, logger) }
    }

    private fun toInstruction(
        entry: JsonObject?,
        logger: Logger,
    ): ReviewInstruction? {
        val name = entry?.text("name")
        val paths = (entry?.get("fileFilters") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val text = entry?.text("instructions")
        if (name == null || paths.isEmpty() || text == null) {
            logger.warn("$PATH: review instruction '${name ?: "?"}' needs name, fileFilters and instructions; ignored")
            return null
        }
        return ReviewInstruction(name, paths, text)
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
