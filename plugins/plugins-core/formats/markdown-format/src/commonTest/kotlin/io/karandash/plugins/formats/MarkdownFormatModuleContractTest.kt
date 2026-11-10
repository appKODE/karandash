package io.karandash.plugins.formats

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class MarkdownFormatModuleContractTest : ModuleContractTest<MarkdownFormatModule>() {
    override fun createModule() = MarkdownFormatModule()

    override fun malformedConfig() = null

    // Config reaches a format per call, so the malformed-config half of the contract is checked here too.
    override suspend fun invokePrimaryWithEmptyInput(module: MarkdownFormatModule) {
        module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))
        module.format(createTestReport(findings = emptyList()), JsonPrimitive("not-a-valid-config"))
    }

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }

    @Test
    fun primaryMethodWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }
}
