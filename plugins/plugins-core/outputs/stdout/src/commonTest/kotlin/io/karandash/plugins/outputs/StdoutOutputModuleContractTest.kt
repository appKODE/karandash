package io.karandash.plugins.outputs

import io.karandash.sdk.ModuleContractTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StdoutOutputModuleContractTest : ModuleContractTest<StdoutOutputModule>() {
    override fun createModule() = StdoutOutputModule()

    override fun malformedConfig() = null

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }

    @Test
    fun stdoutIsTheInteractiveDefaultWithoutExternalSideEffects() {
        val module = StdoutOutputModule()

        assertEquals(
            listOf("stdout", "true", "false"),
            listOf(module.outputId, "${module.isDefaultInteractive}", "${module.hasExternalSideEffects}")
        )
    }
}
