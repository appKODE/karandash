package io.karandash.sdk

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.AbstractTypedReporterModule
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A reporter/output/format config block that fails to decode warns on the run logger, then uses defaults. */
@OptIn(ExperimentalKarandashApi::class)
class TypedConfigDecodeWarningTest {
    @Serializable
    data class Cfg(
        val verbose: Boolean = false,
    )

    private class Reporter : AbstractTypedReporterModule<Cfg>(Cfg.serializer()) {
        override val id = "test.reporter"
        override val name = "R"
        override val version = "0"
        override val formatId = "r"
        val seen = mutableListOf<Cfg>()

        override suspend fun reportTyped(
            findings: List<Finding>,
            passed: Boolean,
            config: Cfg,
        ): String? {
            seen += config
            return null
        }
    }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun invalidBlockWarnsWithoutTheValueAndFallsBack() =
        runTest {
            val logger = RecordingLogger()
            val reporter = Reporter()
            val session = ModuleRegistryImpl().forRun(logger)

            withContext(session.services) {
                reporter.report(emptyList(), true, JsonObject(mapOf("verbose" to JsonPrimitive(SECRET))))
            }

            assertEquals(listOf(Cfg()), reporter.seen)
            val warning = logger.warnings.single()
            assertTrue("reporter 'test.reporter'" in warning, warning)
            assertTrue(SECRET !in warning, "value leaked: $warning")
        }

    @Test
    fun validBlockDoesNotWarn() =
        runTest {
            val logger = RecordingLogger()
            val session = ModuleRegistryImpl().forRun(logger)

            withContext(session.services) {
                Reporter().report(emptyList(), true, JsonObject(mapOf("verbose" to JsonPrimitive(true))))
            }

            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    private companion object {
        const val SECRET = "s3cr3tValue"
    }
}
