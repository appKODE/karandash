package io.karandash.config.pipeline

import io.karandash.http.HttpAuth
import io.karandash.manifest.ExtendsEntry
import io.karandash.platform.PlatformEnvironment
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hiding secret-looking names from JS plugins must not stop the
 * review-bot CI template's variables from loading in the base `env:` block or interpolating, `extends.auth` included.
 */
class CiSecretNamesInterpolationTest {
    private val names = listOf("KARANDASH_GITLAB_TOKEN", "KARANDASH_PROJECT_TOKEN", "BIFROST_API_KEY", "KARANDASH_AI_MAX_TOKENS")
    private val defaults = names.associateWith { "v-$it" }

    private fun expected(name: String) = PlatformEnvironment.getEnv(name) ?: "v-$name"

    @Test
    fun `CI secret names load in the base env block and interpolate`() {
        val base = buildJsonObject {
            putJsonObject("env") { defaults.forEach { (name, value) -> put(name, value) } }
        }
        val declaration = ConfigMapParser.parseEnvBlock(base)
        validateEnvDeclaration(declaration)
        assertEquals(defaults, declaration.defaults)

        names.forEach { name ->
            val denied = mutableSetOf<String>()
            val value = ConfigMapParser.interpolate("\${$name}", declaration.defaults, deniedVars = denied)
            assertTrue(denied.isEmpty(), "$name must not be denied")
            assertEquals(expected(name), value, name)
        }
    }

    @Test
    fun `CI secret names interpolate in extends auth`() {
        names.forEach { name ->
            val data = buildJsonObject {
                put(
                    "extends",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("url", "https://gitlab.example.com/api/v4/projects/1/repository/files/base.yaml/raw")
                                putJsonObject("auth") {
                                    put("type", "custom-header")
                                    put("name", "PRIVATE-TOKEN")
                                    put("value", JsonPrimitive("\${$name}"))
                                }
                            },
                        )
                    },
                )
            }
            val entry = assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(data, defaults).single())
            val auth = assertIs<HttpAuth.CustomHeader>(entry.source.auth)
            assertEquals(expected(name), auth.value, name)
        }
    }
}
