package io.karandash.plugins.ai.analyzers.commentanalysis

import io.karandash.sdk.ModuleContractTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CommentAnalysisAnalyzerModuleContractTest : ModuleContractTest<CommentAnalysisAnalyzerModule>() {
    override fun createModule() = CommentAnalysisAnalyzerModule()

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
