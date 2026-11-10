package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import io.karandash.logger.Logger
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An `extends:` auth value that interpolates to blank is named up front and in the 404 a private repo answers. */
class ExtendsEmptyAuthTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val url = "https://git.example.com/api/v4/projects/1/repository/files/base%2Fconfig.yaml/raw?ref=main"

    private fun fetcherAnswering(status: HttpStatusCode) =
        RemoteConfigFetcher(
            HttpClient(MockEngine) {
                engine { addHandler { respond(ByteReadChannel("not found"), status) } }
            },
        )

    private fun emptyTokenSource() =
        ConfigSource(
            url = url,
            auth = HttpAuth.CustomHeader("PRIVATE-TOKEN", ""),
            emptyAuthVars = listOf(UNSET_VAR),
        )

    @Test
    fun `auth value with an unset variable is recorded on the source`() {
        val source = ConfigMapParser
            .getExtendsSources(
                mapOf<String, Any?>(
                    "extends" to mapOf(
                        "url" to url,
                        "auth" to mapOf("type" to "custom-header", "name" to "PRIVATE-TOKEN", "value" to "\${$UNSET_VAR}"),
                    ),
                ),
            ).single()
        assertEquals(listOf(UNSET_VAR), source.emptyAuthVars)
    }

    @Test
    fun `auth value with a literal token records nothing`() {
        val source = ConfigMapParser
            .getExtendsSources(
                mapOf<String, Any?>("extends" to mapOf("url" to url, "auth" to mapOf("type" to "bearer-token", "token" to "literal"))),
            ).single()
        assertTrue(source.emptyAuthVars.isEmpty())
    }

    @Test
    fun `404 with an empty auth value names the variable`() =
        runTest {
            val e = assertFailsWith<ConfigException> { fetcherAnswering(HttpStatusCode.NotFound).fetch(emptyTokenSource()) }
            assertContains(e.message.orEmpty(), "HTTP 404. Private repo? Check $UNSET_VAR")
        }

    @Test
    fun `conditional fetch 404 with an empty auth value names the variable`() =
        runTest {
            val e = assertFailsWith<ConfigException> {
                fetcherAnswering(
                    HttpStatusCode.NotFound
                ).fetchWithETag(url, null, HttpAuth.CustomHeader("PRIVATE-TOKEN", ""), listOf(UNSET_VAR))
            }
            assertContains(e.message.orEmpty(), "Private repo? Check $UNSET_VAR")
        }

    @Test
    fun `404 without auth hints at a private repo`() =
        runTest {
            val e = assertFailsWith<ConfigException> { fetcherAnswering(HttpStatusCode.NotFound).fetch(ConfigSource(url)) }
            assertContains(e.message.orEmpty(), "HTTP 404. Private repo? Add an auth block")
        }

    @Test
    fun `404 with a real token is a plain not found`() =
        runTest {
            val e = assertFailsWith<ConfigException> {
                fetcherAnswering(HttpStatusCode.NotFound).fetch(ConfigSource(url, auth = HttpAuth.Bearer("set")))
            }
            assertFalse(e.message.orEmpty().contains("Private repo"), e.message)
        }

    @Test
    fun `chain resolution warns about the empty auth before fetching`() =
        runTest {
            val logger = WarnLogger()
            val resolver = ConfigChainResolver(
                formatRegistry = ConfigFormatRegistry(emptyList()),
                fetcher = fetcherAnswering(HttpStatusCode.NotFound),
                logger = logger,
            )
            assertFailsWith<ConfigException> { resolver.buildChain(emptyTokenSource()) }
            assertTrue(logger.warnings.any { "extends auth" in it && UNSET_VAR in it }, logger.warnings.toString())
        }

    @Test
    fun `missing local file reference reports and resolves to empty when a callback is set`() =
        runTest {
            val reported = mutableListOf<String>()
            val element = JsonObject(mapOf("extra" to JsonObject(mapOf("file" to JsonPrimitive("absent-l20.md")))))
            val context = FileResolutionContext.Local(Path("build/tmp/l20-none")) { ref, _ -> reported += ref }

            val resolved = ExternalValueResolver.resolve(element, context) as JsonObject

            assertEquals(JsonPrimitive(""), resolved["extra"])
            assertEquals(listOf("absent-l20.md"), reported)
            assertFailsWith<ConfigException> {
                ExternalValueResolver.resolve(element, FileResolutionContext.Local(Path("build/tmp/l20-none")))
            }
        }

    private companion object {
        const val UNSET_VAR = "KARANDASH_L20_UNSET_TOKEN"
    }
}
