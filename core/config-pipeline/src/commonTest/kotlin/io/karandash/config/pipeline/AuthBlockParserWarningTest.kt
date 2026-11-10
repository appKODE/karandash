package io.karandash.config.pipeline

import io.karandash.logger.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Malformed `auth:` entries are skipped with a warn naming the entry, never its credential values. */
class AuthBlockParserWarningTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun malformedEntriesWarnWithNameAndWithoutSecrets() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put("no-type", buildJsonObject { put("token", SECRET) })
                    put(
                        "bad-type",
                        buildJsonObject {
                            put("type", SECRET)
                            put("password", SECRET)
                        },
                    )
                    put("scalar", JsonPrimitive(SECRET))
                    put(
                        "ok",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", SECRET)
                        },
                    )
                },
            )
        }
        val logger = RecordingLogger()

        val registry = AuthBlockParser.parse(element, logger = logger)

        assertTrue(registry.has("ok"))
        assertEquals(3, logger.warnings.size, "${logger.warnings}")
        listOf("no-type", "bad-type", "scalar").forEach { name ->
            assertTrue(logger.warnings.any { "'$name'" in it }, "no warning for $name: ${logger.warnings}")
        }
        assertTrue(logger.warnings.none { SECRET in it }, "secret leaked: ${logger.warnings}")
    }

    @Test
    fun `auth file-resolution and base-url are live and warn about nothing`() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put(
                        "corp",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", SECRET)
                            put("baseUrl", "https://git.example.com")
                            put("fileResolution", "strict")
                        },
                    )
                },
            )
        }
        val logger = RecordingLogger()

        val registry = AuthBlockParser.parse(element, logger = logger)

        assertTrue(registry.has("corp"))
        assertEquals(emptyList(), logger.warnings)
    }

    private companion object {
        const val SECRET = "hunter2-secret"
    }
}
