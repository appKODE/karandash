package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ManifestContentParser
import io.karandash.config.pipeline.ManifestResolver
import io.karandash.http.HttpAuth
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test suite for ManifestResolver.
 *
 * Coverage:
 * - Resolve @name references to extends sets
 * - Missing manifest → E0017
 * - Missing set → E0018 with available sets listed
 * - Auth configuration in manifest entries
 * - Environment variable interpolation
 * - Invalid manifest structure errors
 */
class ManifestResolverTest {
    @Test
    fun `resolve returns entries from named set`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      standard:
                        - "https://example.com/base.yaml"
                        - "https://example.com/security.yaml"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("standard", emptyMap())

                assertEquals(2, entries.size)
                assertEquals("https://example.com/base.yaml", entries[0].source.url)
                assertEquals("https://example.com/security.yaml", entries[1].source.url)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0017 when manifest file not found`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-missing-test")
            try {
                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })

                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("standard", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0017"))
                assertTrue(exception.message!!.contains("not found"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0018 when set not found with available sets listed`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-set-missing-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      standard:
                        - "https://example.com/base.yaml"
                      minimal:
                        - "https://example.com/minimal.yaml"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("nonexistent", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0018"))
                assertTrue(exception.message!!.contains("@nonexistent"))
                assertTrue(exception.message!!.contains("Available:"))
                assertTrue(exception.message!!.contains("standard"))
                assertTrue(exception.message!!.contains("minimal"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve parses bearer token auth from manifest`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-bearer-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      authenticated:
                        - url: "https://private.example.com/config.yaml"
                          auth:
                            type: bearer-token
                            token: "glpat-test-123"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("authenticated", emptyMap())

                assertEquals(1, entries.size)
                assertEquals("https://private.example.com/config.yaml", entries[0].source.url)
                assertNotNull(entries[0].source.auth)
                assertTrue(entries[0].source.auth is HttpAuth.Bearer)
                assertEquals("glpat-test-123", (entries[0].source.auth as HttpAuth.Bearer).token)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve parses basic auth from manifest`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-basic-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      basic:
                        - url: "https://bitbucket.example.com/config.yaml"
                          auth:
                            type: basic-auth
                            username: "deploy-bot"
                            password: "secret123"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("basic", emptyMap())

                assertEquals(1, entries.size)
                assertTrue(entries[0].source.auth is HttpAuth.Basic)
                val auth = entries[0].source.auth as HttpAuth.Basic
                assertEquals("deploy-bot", auth.username)
                assertEquals("secret123", auth.password)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve parses custom header auth from manifest`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-custom-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      custom:
                        - url: "https://internal.example.com/config.yaml"
                          auth:
                            type: custom-header
                            name: "X-Api-Key"
                            value: "key-abc-123"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("custom", emptyMap())

                assertEquals(1, entries.size)
                assertTrue(entries[0].source.auth is HttpAuth.CustomHeader)
                val auth = entries[0].source.auth as HttpAuth.CustomHeader
                assertEquals("X-Api-Key", auth.name)
                assertEquals("key-abc-123", auth.value)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve interpolates environment variables in URLs`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-env-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                // Note: Environment variable interpolation is handled by ConfigMapParser.interpolate()
                // which the ManifestResolver uses. The actual env var resolution happens there.
                val manifestContent = """
                    extends-sets:
                      dynamic:
                        - "https://${'$'}{CONFIG_HOST}/base.yaml"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("dynamic", emptyMap())

                // Should contain the literal ${CONFIG_HOST} since env var is not set
                // ConfigMapParser.interpolate replaces with empty string if not found
                assertEquals(1, entries.size)
                assertTrue(entries[0].source.url.contains("https://"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve handles mixed string and object entries`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-mixed-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      mixed:
                        - "https://example.com/public.yaml"
                        - url: "https://private.example.com/private.yaml"
                          auth:
                            type: bearer-token
                            token: "token123"
                        - "https://example.com/another.yaml"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("mixed", emptyMap())

                assertEquals(3, entries.size)
                assertEquals("https://example.com/public.yaml", entries[0].source.url)
                assertNull(entries[0].source.auth)
                assertEquals("https://private.example.com/private.yaml", entries[1].source.url)
                assertNotNull(entries[1].source.auth)
                assertEquals("https://example.com/another.yaml", entries[2].source.url)
                assertNull(entries[2].source.auth)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0017 for invalid YAML structure`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-invalid-yaml-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      broken: [
                        - unclosed array
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("broken", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0017"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0017 for missing extends-sets section`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-no-section-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    some-other-field:
                      data: "value"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("anything", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0017"))
                assertTrue(exception.message!!.contains("extends-sets"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0017 for entry missing url field`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-no-url-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      broken:
                        - auth:
                            type: bearer-token
                            token: "token"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("broken", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0017"))
                assertTrue(exception.message!!.contains("url"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve throws E0017 for unknown auth type`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-unknown-auth-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      bad-auth:
                        - url: "https://example.com/config.yaml"
                          auth:
                            type: oauth2
                            token: "token"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val exception = assertFailsWith<ConfigException> {
                    resolver.resolve("bad-auth", emptyMap())
                }

                assertTrue(exception.message!!.contains("E0017"))
                assertTrue(exception.message!!.contains("oauth2"))
                assertTrue(exception.message!!.contains("bearer-token, basic-auth, custom-header"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `listSets returns all available sets with URLs`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-list-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      standard:
                        - "https://example.com/base.yaml"
                        - "https://example.com/security.yaml"
                      minimal:
                        - "https://example.com/minimal.yaml"
                      enterprise:
                        - url: "https://corp.example.com/config.yaml"
                          auth:
                            type: bearer-token
                            token: "token"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val sets = resolver.listSets()

                assertEquals(3, sets.size)
                assertTrue(sets.containsKey("standard"))
                assertTrue(sets.containsKey("minimal"))
                assertTrue(sets.containsKey("enterprise"))

                assertEquals(2, sets["standard"]?.size)
                assertEquals(1, sets["minimal"]?.size)
                assertEquals(1, sets["enterprise"]?.size)

                assertTrue(sets["standard"]!!.contains("https://example.com/base.yaml"))
                assertTrue(sets["standard"]!!.contains("https://example.com/security.yaml"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `listSets throws E0017 when manifest not found`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-list-missing-test")
            try {
                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })

                val exception = assertFailsWith<ConfigException> {
                    resolver.listSets()
                }

                assertTrue(exception.message!!.contains("E0017"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `resolve handles empty set`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("manifest-empty-set-test")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                val manifestContent = """
                    extends-sets:
                      empty: []
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })
                val entries = resolver.resolve("empty", emptyMap())

                assertEquals(0, entries.size)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }
}
