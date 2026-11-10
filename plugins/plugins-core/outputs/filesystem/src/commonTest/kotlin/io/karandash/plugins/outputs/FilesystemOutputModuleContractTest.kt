package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FilesystemOutputModuleContractTest : ModuleContractTest<FilesystemOutputModule>() {
    override fun createModule() = FilesystemOutputModule()

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
    fun outputWritesTheFormattedBodyToOutputFile() =
        runTest {
            val file = Path(TestFiles.createTestTempDirectory("fs-output"), "fs-output.json")
            try {
                val module = createModule()
                module.initialize(testContext())
                val config = JsonObject(mapOf("outputFile" to JsonPrimitive(file.toString())))
                module.output(FormattedReport(formatId = "json", body = "{\"passed\":true}"), config)
                module.shutdown()
                assertEquals("{\"passed\":true}", SystemFileSystem.source(file).buffered().use { it.readString() })
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }
}
