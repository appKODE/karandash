package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ApiUrlParser
import io.karandash.config.pipeline.ExternalValueResolver
import io.karandash.config.pipeline.FileResolutionContext
import io.karandash.http.HttpAuth
import io.karandash.logger.Logger
import io.karandash.manifest.RemoteFileResolution
import kotlinx.coroutines.test.runTest
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

/**
 * Tests for [ExternalValueResolver] remote file reference resolution.
 *
 * Uses a mock fetcher lambda instead of real HTTP calls.
 */
class ExternalValueResolverRemoteTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFileHelpers.createTempDir("external-value-remote")
    }

    @AfterTest
    fun tearDown() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun remoteContext(
        baseUrl: String = "https://gitlab.example.com/devops/shared/-/raw/main/.karandash",
        auth: HttpAuth? = HttpAuth.Bearer("test-token"),
        responses: Map<String, String> = emptyMap(),
        localBasePath: Path? = null,
        resolution: RemoteFileResolution = RemoteFileResolution.FALLBACK_LOCAL,
        warnings: MutableList<String>? = null,
    ): FileResolutionContext.Remote =
        FileResolutionContext.Remote(
            baseUrl = baseUrl,
            auth = auth,
            fetcher = { url, _ ->
                responses[url] ?: throw ConfigException("[E0023] File not found at $url")
            },
            localBasePath = localBasePath,
            resolution = resolution,
            onWarning = warnings?.let { list -> { msg -> list.add(msg) } },
        )

    @Test
    fun `resolves remote file reference against base URL`() =
        runTest {
            val baseUrl = "https://gitlab.example.com/devops/shared/-/raw/main/.karandash"
            val expectedUrl = "$baseUrl/patterns/title-regex.txt"
            val context = remoteContext(
                baseUrl = baseUrl,
                responses = mapOf(expectedUrl to "^(feat|fix):"),
            )

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
                                        put("file", "./patterns/title-regex.txt")
                                    }
                                )
                            }
                        )
                    }
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val rules = (result as JsonObject)["rules"] as JsonObject
            val titleFormat = rules["title-format"] as JsonObject
            val pattern = titleFormat["pattern"] as JsonPrimitive

            assertEquals("^(feat|fix):", pattern.content)
        }

    @Test
    fun `resolves remote file reference without dot-slash prefix`() =
        runTest {
            val baseUrl = "https://gitlab.example.com/devops/shared/-/raw/main/.karandash"
            val expectedUrl = "$baseUrl/patterns/regex.txt"
            val context = remoteContext(
                baseUrl = baseUrl,
                responses = mapOf(expectedUrl to "pattern-content"),
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "patterns/regex.txt")
                    }
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive

            assertEquals("pattern-content", value.content)
        }

    @Test
    fun `trims trailing whitespace from remote file content`() =
        runTest {
            val baseUrl = "https://example.com/config"
            val expectedUrl = "$baseUrl/file.txt"
            val context = remoteContext(
                baseUrl = baseUrl,
                responses = mapOf(expectedUrl to "content\n\n"),
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "file.txt")
                    }
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive

            assertEquals("content", value.content)
        }

    @Test
    fun `passes auth to fetcher lambda`() =
        runTest {
            val baseUrl = "https://example.com/config"
            val expectedUrl = "$baseUrl/file.txt"
            val expectedAuth = HttpAuth.Bearer("my-secret-token")
            var capturedAuth: HttpAuth? = null

            val context = FileResolutionContext.Remote(
                baseUrl = baseUrl,
                auth = expectedAuth,
                fetcher = { _, auth ->
                    capturedAuth = auth
                    "content"
                },
                resolution = RemoteFileResolution.FALLBACK_LOCAL,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "file.txt")
                    }
                )
            }

            ExternalValueResolver.resolve(input, context)
            assertEquals(expectedAuth, capturedAuth)
        }

    @Test
    fun `skips resolution under security denylist keys`() =
        runTest {
            val context = remoteContext(responses = emptyMap())

            val input = buildJsonObject {
                put(
                    "auth",
                    buildJsonObject {
                        put("file", "./secret.txt")
                    }
                )
                put(
                    "token",
                    buildJsonObject {
                        put("file", "./token.txt")
                    }
                )
                put(
                    "password",
                    buildJsonObject {
                        put("file", "./pass.txt")
                    }
                )
            }

            val result = ExternalValueResolver.resolve(input, context) as JsonObject

            assertTrue((result["auth"] as JsonObject).containsKey("file"))
            assertTrue((result["token"] as JsonObject).containsKey("file"))
            assertTrue((result["password"] as JsonObject).containsKey("file"))
        }

    @Test
    fun `rejects absolute URL in file reference`() =
        runTest {
            val context = remoteContext()

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "https://evil.com/steal.txt")
                    }
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("Absolute URLs are not allowed"))
        }

    @Test
    fun `rejects absolute path in remote file reference`() =
        runTest {
            val context = remoteContext()

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "/etc/passwd")
                    }
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("Absolute paths are not allowed"))
        }

    @Test
    fun `rejects path traversal escaping config directory`() =
        runTest {
            val context = remoteContext(
                baseUrl = "https://example.com/org/repo/-/raw/main/.karandash",
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "../../../../etc/passwd")
                    }
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("path traversal"))
        }

    @Test
    fun `throws on empty file path in remote context`() =
        runTest {
            val context = remoteContext()

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "")
                    }
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("empty"))
        }

    @Test
    fun `throws when remote fetch fails`() =
        runTest {
            val context = remoteContext(responses = emptyMap())

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "nonexistent.txt")
                    }
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
        }

    @Test
    fun `resolves nested remote file references`() =
        runTest {
            val baseUrl = "https://example.com/config"
            val context = remoteContext(
                baseUrl = baseUrl,
                responses = mapOf(
                    "$baseUrl/patterns/title.txt" to "^feat:",
                    "$baseUrl/patterns/branch.txt" to "^(feature|bugfix)/",
                ),
            )

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
                                        put("file", "./patterns/title.txt")
                                    }
                                )
                                put("enabled", true)
                            }
                        )
                        put(
                            "branch-naming",
                            buildJsonObject {
                                put(
                                    "pattern",
                                    buildJsonObject {
                                        put("file", "./patterns/branch.txt")
                                    }
                                )
                            }
                        )
                    }
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val rules = (result as JsonObject)["rules"] as JsonObject
            val titlePattern = (rules["title-format"] as JsonObject)["pattern"] as JsonPrimitive
            val branchPattern = (rules["branch-naming"] as JsonObject)["pattern"] as JsonPrimitive

            assertEquals("^feat:", titlePattern.content)
            assertEquals("^(feature|bugfix)/", branchPattern.content)
        }

    // --- URL resolution unit tests ---

    @Test
    fun `resolveRemoteUrl handles dot-slash prefix`() {
        val result = ExternalValueResolver.resolveRemoteUrl(
            "https://example.com/config",
            "./patterns/title.txt",
        )
        assertEquals("https://example.com/config/patterns/title.txt", result)
    }

    @Test
    fun `resolveRemoteUrl normalizes parent segments`() {
        val result = ExternalValueResolver.resolveRemoteUrl(
            "https://example.com/repo/dir",
            "../other/file.txt",
        )
        assertEquals("https://example.com/repo/other/file.txt", result)
    }

    @Test
    fun `resolveRemoteUrl handles plain relative path`() {
        val result = ExternalValueResolver.resolveRemoteUrl(
            "https://example.com/config",
            "file.txt",
        )
        assertEquals("https://example.com/config/file.txt", result)
    }

    @Test
    fun `validateRemoteFilePath allows sibling directory traversal`() {
        ExternalValueResolver.validateRemoteFilePath(
            "../sibling/file.txt",
            "https://example.com/org/repo/dir",
        )
    }

    @Test
    fun `validateRemoteFilePath blocks escape above origin path`() {
        val ex = assertFailsWith<ConfigException> {
            ExternalValueResolver.validateRemoteFilePath(
                "../../../../etc/passwd",
                "https://example.com/org/repo/-/raw/main/.karandash",
            )
        }
        assertTrue(ex.message!!.contains("path traversal"))
    }

    // --- file-local tests ---

    @Test
    fun `file-local resolves against local CWD in remote context`() =
        runTest {
            val localFile = Path(tempDir, "local-pattern.txt")
            TestFileHelpers.writeFile(localFile, "local-content")

            val context = remoteContext(
                responses = emptyMap(),
                localBasePath = tempDir,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file-local", "local-pattern.txt")
                    },
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("local-content", value.content)
        }

    @Test
    fun `file-local fails when local file not found`() =
        runTest {
            val context = remoteContext(localBasePath = tempDir)

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file-local", "nonexistent.txt")
                    },
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("Local file not found"))
        }

    // --- file-remote tests ---

    @Test
    fun `file-remote resolves against remote URL`() =
        runTest {
            val baseUrl = "https://example.com/config"
            val context = remoteContext(
                baseUrl = baseUrl,
                responses = mapOf("$baseUrl/data.txt" to "remote-data"),
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file-remote", "data.txt")
                    },
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("remote-data", value.content)
        }

    @Test
    fun `file-remote never falls back to local`() =
        runTest {
            val localFile = Path(tempDir, "data.txt")
            TestFileHelpers.writeFile(localFile, "local-data")

            val context = remoteContext(
                responses = emptyMap(),
                localBasePath = tempDir,
                resolution = RemoteFileResolution.FALLBACK_LOCAL,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file-remote", "data.txt")
                    },
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
        }

    // --- fallback behavior tests ---

    @Test
    fun `file falls back to local when fallback enabled and remote fails`() =
        runTest {
            val localFile = Path(tempDir, "pattern.txt")
            TestFileHelpers.writeFile(localFile, "local-fallback")
            val warnings = mutableListOf<String>()

            val context = remoteContext(
                responses = emptyMap(),
                localBasePath = tempDir,
                resolution = RemoteFileResolution.FALLBACK_LOCAL,
                warnings = warnings,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "pattern.txt")
                    },
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("local-fallback", value.content)
            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("Falling back to local file"))
        }

    @Test
    fun `file does not fall back when fallback disabled`() =
        runTest {
            val localFile = Path(tempDir, "pattern.txt")
            TestFileHelpers.writeFile(localFile, "local-fallback")

            val context = remoteContext(
                responses = emptyMap(),
                localBasePath = tempDir,
                resolution = RemoteFileResolution.STRICT,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "pattern.txt")
                    },
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
        }

    // --- LOCAL mode tests ---

    @Test
    fun `LOCAL resolves file locally without remote call`() =
        runTest {
            val localFile = Path(tempDir, "local-data.txt")
            TestFileHelpers.writeFile(localFile, "local-content")
            var fetcherCalled = false

            val context = FileResolutionContext.Remote(
                baseUrl = "https://example.com/config",
                auth = null,
                fetcher = { _, _ ->
                    fetcherCalled = true
                    "remote"
                },
                localBasePath = tempDir,
                resolution = RemoteFileResolution.LOCAL,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "local-data.txt")
                    },
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("local-content", value.content)
            assertEquals(false, fetcherCalled)
        }

    @Test
    fun `LOCAL fails when local file not found`() =
        runTest {
            val context = remoteContext(
                localBasePath = tempDir,
                resolution = RemoteFileResolution.LOCAL,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "nonexistent.txt")
                    },
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("Local file not found"))
        }

    // --- FALLBACK_REMOTE mode tests ---

    @Test
    fun `FALLBACK_REMOTE resolves locally when file exists`() =
        runTest {
            val localFile = Path(tempDir, "data.txt")
            TestFileHelpers.writeFile(localFile, "local-value")
            var fetcherCalled = false

            val context = FileResolutionContext.Remote(
                baseUrl = "https://example.com/config",
                auth = null,
                fetcher = { _, _ ->
                    fetcherCalled = true
                    "remote-value"
                },
                localBasePath = tempDir,
                resolution = RemoteFileResolution.FALLBACK_REMOTE,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "data.txt")
                    },
                )
            }

            val result = ExternalValueResolver.resolve(input, context)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("local-value", value.content)
            assertEquals(false, fetcherCalled)
        }

    @Test
    fun `FALLBACK_REMOTE falls back to remote when local missing`() =
        runTest {
            val baseUrl = "https://example.com/config"
            val warnings = mutableListOf<String>()

            val context = FileResolutionContext.Remote(
                baseUrl = baseUrl,
                auth = null,
                fetcher = { _, _ -> "remote-fallback-content" },
                localBasePath = tempDir,
                resolution = RemoteFileResolution.FALLBACK_REMOTE,
                onWarning = { warnings.add(it) },
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "missing.txt")
                    },
                )
            }

            val debug = mutableListOf<String>()
            val logger = object : Logger {
                override fun debug(message: String) {
                    debug.add(message)
                }

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings.add(message)
                }

                override fun error(message: String) = Unit
            }
            val result = ExternalValueResolver.resolve(input, context, logger)
            val value = (result as JsonObject)["value"] as JsonPrimitive
            assertEquals("remote-fallback-content", value.content)
            // fallback-remote is an explicit choice, so the remote read is debug, not a warning
            assertEquals(emptyList(), warnings)
            assertTrue(debug.single().contains("Falling back to remote"), debug.toString())
        }

    @Test
    fun `FALLBACK_REMOTE fails when both local and remote fail`() =
        runTest {
            val context = remoteContext(
                responses = emptyMap(),
                localBasePath = tempDir,
                resolution = RemoteFileResolution.FALLBACK_REMOTE,
            )

            val input = buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("file", "nowhere.txt")
                    },
                )
            }

            val ex = assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(input, context)
            }
            assertTrue(ex.message!!.contains("E0023"))
            assertTrue(ex.message!!.contains("not found locally"))
            assertTrue(ex.message!!.contains("remote fetch"))
        }

    // ── API URL file reference resolution ───────────────────────────────

    @Test
    fun `file reference in GitLab API-fetched config resolves to valid API URL`() =
        runTest {
            val configUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/configs%2Fbase.yaml/raw?ref=main"
            val parsed = ApiUrlParser.tryParse(configUrl)
            // "patterns/title.txt" relative to "configs/" → "configs/patterns/title.txt"
            val expectedFileUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/configs%2Fpatterns%2Ftitle.txt/raw?ref=main"

            val context = FileResolutionContext.Remote(
                baseUrl = "",
                auth = HttpAuth.Bearer("token"),
                fetcher = { url, _ ->
                    if (url == expectedFileUrl) "^(feat|fix):" else throw ConfigException("[E0023] Not found: $url")
                },
                resolution = RemoteFileResolution.STRICT,
                parsedApiUrl = parsed,
            )

            val input = buildJsonObject {
                put("pattern", buildJsonObject { put("file", "patterns/title.txt") })
            }
            val result = ExternalValueResolver.resolve(input, context)
            val pattern = (result as JsonObject)["pattern"]
            assertEquals(JsonPrimitive("^(feat|fix):"), pattern)
        }

    @Test
    fun `file reference with parent traversal in GitLab API context`() =
        runTest {
            val configUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/configs%2Fchild%2Fbase.yaml/raw?ref=main"
            val parsed = ApiUrlParser.tryParse(configUrl)
            // "../shared/data.txt" from "configs/child/" → "configs/shared/data.txt"
            val expectedFileUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/configs%2Fshared%2Fdata.txt/raw?ref=main"

            val context = FileResolutionContext.Remote(
                baseUrl = "",
                auth = HttpAuth.Bearer("token"),
                fetcher = { url, _ ->
                    if (url == expectedFileUrl) "shared data" else throw ConfigException("[E0023] Not found: $url")
                },
                resolution = RemoteFileResolution.STRICT,
                parsedApiUrl = parsed,
            )

            val input = buildJsonObject {
                put("data", buildJsonObject { put("file", "../shared/data.txt") })
            }
            val result = ExternalValueResolver.resolve(input, context)
            val data = (result as JsonObject)["data"]
            assertEquals(JsonPrimitive("shared data"), data)
        }

    @Test
    fun `non-API URL falls back to PathSecurity resolution`() =
        runTest {
            val baseUrl = "https://example.com/configs"
            val expectedUrl = "$baseUrl/patterns/title.txt"

            val context = FileResolutionContext.Remote(
                baseUrl = baseUrl,
                auth = null,
                fetcher = { url, _ ->
                    if (url == expectedUrl) "content" else throw ConfigException("[E0023] Not found: $url")
                },
                resolution = RemoteFileResolution.STRICT,
                parsedApiUrl = null,
            )

            val input = buildJsonObject {
                put("data", buildJsonObject { put("file", "patterns/title.txt") })
            }
            val result = ExternalValueResolver.resolve(input, context)
            val data = (result as JsonObject)["data"]
            assertEquals(JsonPrimitive("content"), data)
        }
}
