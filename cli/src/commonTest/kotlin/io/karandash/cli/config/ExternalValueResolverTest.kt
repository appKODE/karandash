package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.api.support.mapToJsonElement
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.config.pipeline.ExternalValueResolver
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExternalValueResolverTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFileHelpers.createTempDir("external-value-resolver")
    }

    @AfterTest
    fun tearDown() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `resolves file reference to string content`() {
        val filePath = Path(tempDir, "pattern.txt")
        TestFileHelpers.writeFile(filePath, "^(feat|fix):")

        val input = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "title-format",
                        buildJsonObject {
                            put(
                                "pattern",
                                buildJsonObject {
                                    put("file", filePath.toString())
                                }
                            )
                        }
                    )
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val rules = (result as JsonObject)["rules"] as JsonObject
        val titleFormat = rules["title-format"] as JsonObject
        val pattern = titleFormat["pattern"] as JsonPrimitive

        assertEquals("^(feat|fix):", pattern.content)
    }

    @Test
    fun `resolves relative file path against base directory`() {
        val filePath = Path(tempDir, "my-pattern.txt")
        TestFileHelpers.writeFile(filePath, "hello world")

        val input = buildJsonObject {
            put(
                "value",
                buildJsonObject {
                    put("file", "my-pattern.txt")
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val value = (result as JsonObject)["value"] as JsonPrimitive

        assertEquals("hello world", value.content)
    }

    @Test
    fun `trims trailing whitespace from file content`() {
        val filePath = Path(tempDir, "trailing.txt")
        TestFileHelpers.writeFile(filePath, "content\n\n")

        val input = buildJsonObject {
            put(
                "value",
                buildJsonObject {
                    put("file", filePath.toString())
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val value = (result as JsonObject)["value"] as JsonPrimitive

        assertEquals("content", value.content)
    }

    @Test
    fun `leaves inline string values unchanged`() {
        val input = buildJsonObject {
            put("pattern", "^(feat|fix):")
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val pattern = (result as JsonObject)["pattern"] as JsonPrimitive

        assertEquals("^(feat|fix):", pattern.content)
    }

    @Test
    fun `skips resolution under auth parent key`() {
        val filePath = Path(tempDir, "secret.txt")
        TestFileHelpers.writeFile(filePath, "my-secret")

        val input = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put("file", filePath.toString())
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val auth = (result as JsonObject)["auth"] as JsonObject

        assertTrue(auth.containsKey("file"), "file key should remain under auth")
    }

    @Test
    fun `skips resolution under token parent key`() {
        val filePath = Path(tempDir, "token.txt")
        TestFileHelpers.writeFile(filePath, "my-token")

        val input = buildJsonObject {
            put(
                "token",
                buildJsonObject {
                    put("file", filePath.toString())
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val token = (result as JsonObject)["token"] as JsonObject

        assertTrue(token.containsKey("file"), "file key should remain under token")
    }

    @Test
    fun `skips resolution under password parent key`() {
        val filePath = Path(tempDir, "pass.txt")
        TestFileHelpers.writeFile(filePath, "my-password")

        val input = buildJsonObject {
            put(
                "password",
                buildJsonObject {
                    put("file", filePath.toString())
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val password = (result as JsonObject)["password"] as JsonObject

        assertTrue(password.containsKey("file"), "file key should remain under password")
    }

    @Test
    fun `throws on missing file`() {
        val input = buildJsonObject {
            put(
                "value",
                buildJsonObject {
                    put("file", "/nonexistent/path/file.txt")
                }
            )
        }

        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.resolve(input, tempDir)
        }
        assertTrue(ex.message!!.contains("E0023"))
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun `throws on empty file path`() {
        val input = buildJsonObject {
            put(
                "value",
                buildJsonObject {
                    put("file", "")
                }
            )
        }

        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.resolve(input, tempDir)
        }
        assertTrue(ex.message!!.contains("E0023"))
        assertTrue(ex.message!!.contains("empty"))
    }

    @Test
    fun `resolves nested file references`() {
        val patternFile = Path(tempDir, "nested-pattern.txt")
        TestFileHelpers.writeFile(patternFile, "^fix:")

        val input = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "title-format",
                        buildJsonObject {
                            put(
                                "pattern",
                                buildJsonObject {
                                    put("file", patternFile.toString())
                                }
                            )
                            put("enabled", true)
                        }
                    )
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val rules = (result as JsonObject)["rules"] as JsonObject
        val titleFormat = rules["title-format"] as JsonObject
        val pattern = titleFormat["pattern"] as JsonPrimitive
        val enabled = titleFormat["enabled"] as JsonPrimitive

        assertEquals("^fix:", pattern.content)
        assertEquals("true", enabled.content)
    }

    @Test
    fun `does not resolve objects with multiple keys as file references`() {
        val input = buildJsonObject {
            put(
                "value",
                buildJsonObject {
                    put("file", "some-path.txt")
                    put("encoding", "utf-8")
                }
            )
        }

        val result = ExternalValueResolver.resolve(input, tempDir)
        val value = (result as JsonObject)["value"] as JsonObject

        assertTrue(value.containsKey("file"), "multi-key object should not be treated as file reference")
        assertTrue(value.containsKey("encoding"))
    }

    @Test
    fun `integration with ConfigMapParser parseFullConfig receives pre-resolved data`() {
        // File resolution is handled by ConfigChainParser upstream;
        // ConfigMapParser receives already-resolved values.
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf(
                    "pattern" to "^(feat|fix|docs):",
                ),
            ),
        )

        val config = ConfigMapParser.parseFullConfig(mapToJsonElement(data)).config
        val titleFormatConfig = config.rules.ruleConfigs["titleFormat"]

        assertTrue(titleFormatConfig is JsonObject)
        val pattern = titleFormatConfig["pattern"] as JsonPrimitive
        assertEquals("^(feat|fix|docs):", pattern.content)
    }
}
