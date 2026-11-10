package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleInitHelperTest {
    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"
        override val logger: Logger = NoOpLogger

        override fun env(name: String): String? = null
    }

    @Test
    fun `missing credentials make the module unavailable`() {
        val registered = initializeWithCredentials<String>(
            context,
            "GitLab",
            "provider",
            resolveCredentials = { error("token missing") },
            createClient = { error("must not build a client without credentials") },
        )

        assertFalse(registered)
    }

    @Test
    fun `optional credentials keep the module available`() {
        val registered = initializeWithCredentials<String>(
            context,
            "Gerrit",
            "provider",
            resolveCredentials = { null },
            credentialsOptional = true,
            createClient = { error("must not build a client without credentials") },
        )

        assertTrue(registered)
    }

    @Test
    fun `resolved credentials build the client and register the module`() {
        var built: String? = null

        val registered = initializeWithCredentials(
            context,
            "GitLab",
            "provider",
            resolveCredentials = { "tok" },
            createClient = { built = it },
        )

        assertTrue(registered)
        assertEquals("tok", built)
    }

    @Test
    fun cancellationIsRethrownNotReadAsMissingCredentials() {
        assertFailsWith<CancellationException> {
            initializeWithCredentials<String>(
                context,
                "GitLab",
                "provider",
                resolveCredentials = { throw CancellationException("run cancelled") },
                createClient = {},
            )
        }
    }
}
