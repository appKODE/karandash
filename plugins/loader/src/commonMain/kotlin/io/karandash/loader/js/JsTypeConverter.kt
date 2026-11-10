package io.karandash.loader.js

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Stateless utility for Kotlin-to-JS JSON marshalling.
 * Provides conversion methods for types used in the QuickJS plugin bridge.
 */
object JsTypeConverter {
    private val json = KarandashJson.Plain

    /**
     * Convert MergeRequest to JSON string for JS consumption.
     */
    fun mergeRequestToJson(mr: MergeRequest): String = json.encodeToString(mr)

    /**
     * Parse JSON string to list of findings.
     */
    fun findingsFromJson(jsonStr: String): List<Finding> = json.decodeFromString(jsonStr)

    /**
     * Convert findings list to JSON string.
     */
    fun findingsToJson(findings: List<Finding>): String = json.encodeToString(findings)

    /**
     * Parse JSON string to list of strings.
     *
     * Delegates to [io.karandash.js.JsUtils.stringListFromJson].
     */
    fun stringListFromJson(jsonStr: String): List<String> =
        io.karandash.js.JsUtils
            .stringListFromJson(jsonStr)

    /**
     * Escape string for safe embedding in JavaScript code.
     * Handles backslashes, quotes, and control characters.
     *
     * Delegates to [io.karandash.js.JsUtils.escapeForJs].
     */
    fun escapeForJs(s: String): String =
        io.karandash.js.JsUtils
            .escapeForJs(s)

    /**
     * Parse a JSON string returned by a JS plugin into a [JsonObject].
     * Returns an empty object on parse failure or if the JSON is not an object.
     */
    fun configFromJson(jsonStr: String?): JsonObject {
        if (jsonStr.isNullOrBlank()) return EMPTY
        return try {
            Json.parseToJsonElement(jsonStr) as? JsonObject ?: EMPTY
        } catch (ignored: IllegalArgumentException) {
            EMPTY
        }
    }

    private val EMPTY = JsonObject(emptyMap())
}
