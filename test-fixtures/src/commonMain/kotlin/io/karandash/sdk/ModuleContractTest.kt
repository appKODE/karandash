package io.karandash.sdk

import io.karandash.api.Module
import io.karandash.api.PluginContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Base test class that verifies module lifecycle contracts.
 *
 * Subclass this for each concrete module to automatically get lifecycle
 * invariant checks (docs/development/conventions.md "Module Contract Tests"). Override [createModule] and optionally
 * [validConfig], [malformedConfig] and [invokePrimaryWithEmptyInput].
 *
 * Call the individual verify methods from `@Test` methods in your concrete test.
 */
abstract class ModuleContractTest<M : Module> {
    /** Creates a fresh, uninitialized module instance for each test. */
    abstract fun createModule(): M

    /** Valid config for [initialize]. Override for module-specific config. */
    open fun validConfig(): JsonElement = JsonObject(emptyMap())

    /** Malformed config that should cause [initialize] to return `false`. Return `null` to skip. */
    open fun malformedConfig(): JsonElement? = JsonPrimitive("not-a-valid-config")

    /**
     * Calls the module's primary method with empty input (contract rule 5). Override in suites that call
     * [verifyPrimaryMethodWithEmptyInput]; the default fails so a suite cannot claim rule 5 without wiring it.
     */
    open suspend fun invokePrimaryWithEmptyInput(module: M) {
        error("${module.id}: override invokePrimaryWithEmptyInput() to verify contract rule 5")
    }

    fun testContext(config: JsonElement = validConfig()): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = config
            override val logger: Logger = NoOpLogger
            override val hostVersion: String = "test"
            override val workingDirectory: String = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    suspend fun verifyShutdownWithoutInitialize() {
        val module = createModule()
        module.shutdown()
    }

    suspend fun verifyShutdownIsIdempotent() {
        val module = createModule()
        module.shutdown()
        module.shutdown()
    }

    suspend fun verifyInitializeWithValidConfig() {
        val module = createModule()
        val result = module.initialize(testContext(validConfig()))
        require(result) { "${module.id}: initialize() should return true for valid config" }
        module.shutdown()
    }

    suspend fun verifyInitializeWithMalformedConfig() {
        val badConfig = malformedConfig() ?: return
        val module = createModule()
        val result = module.initialize(testContext(badConfig))
        require(!result) { "${module.id}: initialize() should return false for malformed config" }
        module.shutdown()
    }

    suspend fun verifyShutdownAfterInitialize() {
        val module = createModule()
        module.initialize(testContext())
        module.shutdown()
    }

    /** Contract rule 5: the primary method with empty input does not throw. */
    suspend fun verifyPrimaryMethodWithEmptyInput() {
        val module = createModule()
        module.initialize(testContext())
        invokePrimaryWithEmptyInput(module)
        module.shutdown()
    }
}
