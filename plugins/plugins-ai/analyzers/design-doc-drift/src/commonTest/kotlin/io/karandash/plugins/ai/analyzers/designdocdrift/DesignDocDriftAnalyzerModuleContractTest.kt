package io.karandash.plugins.ai.analyzers.designdocdrift

import io.karandash.sdk.ModuleContractTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DesignDocDriftAnalyzerModuleContractTest : ModuleContractTest<DesignDocDriftAnalyzerModule>() {
    override fun createModule() = DesignDocDriftAnalyzerModule()

    override fun malformedConfig() = null

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }
}
