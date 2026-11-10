package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** a JS plugin cannot read credential-looking env vars through `ctx.env`. */
class JsEnvSecretsTest {
    private val engine = QuickJs.create(Dispatchers.Default)

    @AfterTest
    fun close() = engine.close()

    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "1.0.0"
        override val workingDirectory: String = "/test"

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun env(name: String): String = "value-of-$name"
    }

    @Test
    fun `JS env hides token key secret and password names`() =
        runTest {
            JsContextInjector(engine, context).inject()

            listOf("GITLAB_TOKEN", "OPENAI_API_KEY", "CLIENT_SECRET", "DB_PASSWORD").forEach { name ->
                assertNull(engine.evaluate<Any?>("__karandash_env('$name')"), name)
            }
            assertEquals("value-of-CI_PROJECT_ID", engine.evaluate<Any?>("__karandash_env('CI_PROJECT_ID')"))
        }

    @Test
    fun `JS env hides the CI template token names that config interpolation still resolves`() =
        runTest {
            JsContextInjector(engine, context).inject()

            listOf("KARANDASH_GITLAB_TOKEN", "KARANDASH_PROJECT_TOKEN", "BIFROST_API_KEY", "KARANDASH_AI_MAX_TOKENS").forEach { name ->
                assertNull(engine.evaluate<Any?>("__karandash_env('$name')"), name)
            }
        }
}
