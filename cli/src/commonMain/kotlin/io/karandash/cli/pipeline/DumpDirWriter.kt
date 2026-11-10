@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.run.RunDump
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.sdk.Logger
import io.karandash.security.TextRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `validate --dump-dir` [RunDump]: each write lands under [dir] with its basename prefixed by a run-wide
 * sequence number (`0007-plan.json`), so `ls` shows the order of transitions. Content is redacted best-effort
 * (see [redact]);
 * a failed write warns and the run goes on. Thread-safe: parallel rules write concurrently.
 */
internal class DumpDirWriter private constructor(
    private val dir: Path,
    private val logger: Logger,
) : RunDump {
    private val sequence = MutableStateFlow(0)

    override suspend fun write(
        name: String,
        content: String,
    ) {
        val n = sequence.updateAndGet { it + 1 }
        val relative = sequenced(sanitize(name), n)
        val path = Path(dir, relative)
        try {
            withContext(Dispatchers.IO) {
                path.safeParent?.let { createDirectories(it) }
                path.writeText(redact(content))
            }
        } catch (e: IOException) {
            logger.warn("--dump-dir: could not write $path: ${e.message}")
        }
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._/-]")
        private const val SEQUENCE_WIDTH = 4
        private val SECRET_KEY = Regex("(?i)password|secret|token|api[_-]?key|private[_-]?key")
        private val NOT_SECRET = Regex("true|false|null|\\d+")

        /**
         * [content] with secrets redacted. A JSON document is redacted value by value and stays valid JSON: a string
         * under a secret-named key (`apiKey`) is replaced unless it is a number or a boolean/null literal
         * (`aiTokensUsed`), and every other string goes through [TextRedactor.redactForLog]. Anything else is
         * redacted as a log line.
         */
        fun redact(content: String): String {
            val json = try {
                KarandashJson.Pretty.parseToJsonElement(content)
            } catch (e: SerializationException) {
                return TextRedactor.redactForLog(content)
            }
            return KarandashJson.Pretty.encodeToString(JsonElement.serializer(), redactJson(json, key = null))
        }

        private fun redactJson(
            element: JsonElement,
            key: String?,
        ): JsonElement =
            when (element) {
                is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactJson(v, k) })
                is JsonArray -> JsonArray(element.map { redactJson(it, key = null) })
                is JsonPrimitive -> if (element.isString) JsonPrimitive(redactString(key, element.content)) else element
            }

        private fun redactString(
            key: String?,
            value: String,
        ): String =
            if (key != null && SECRET_KEY.containsMatchIn(key) && !NOT_SECRET.matches(value)) {
                TextRedactor.REDACTED
            } else {
                TextRedactor.redactForLog(value)
            }

        /** Creates [dir] with a `.gitignore` ignoring everything; `null` (after a warning) when that fails. */
        suspend fun create(
            dir: String,
            logger: Logger,
        ): DumpDirWriter? {
            val path = Path(dir)
            return try {
                withContext(Dispatchers.IO) {
                    createDirectories(path)
                    Path(path, ".gitignore").writeText("*\n")
                }
                DumpDirWriter(path, logger)
            } catch (e: IOException) {
                logger.warn("--dump-dir: cannot create $dir, nothing is dumped: ${e.message}")
                null
            }
        }

        /** [name] as a relative path inside the dump dir: safe characters only, no `.`/`..` or empty segments. */
        fun sanitize(name: String): String =
            UNSAFE
                .replace(name, "_")
                .split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
                .ifEmpty { "unnamed" }

        /** [relative] with its basename prefixed by the zero-padded sequence number [n]. */
        fun sequenced(
            relative: String,
            n: Int,
        ): String {
            val slash = relative.lastIndexOf('/')
            val prefix = n.toString().padStart(SEQUENCE_WIDTH, '0') + "-"
            return relative.substring(0, slash + 1) + prefix + relative.substring(slash + 1)
        }
    }
}
