package io.karandash.cli.commands.validate

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** an explicit config path that does not exist raises CONFIG_FILE_NOT_FOUND (E0005). */
class MissingConfigErrorCodeTest {
    @Test
    fun `a missing explicit config path carries E0005`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("missing-config")
            try {
                val missing = Path(dir, "nope.yaml").toString()
                val pipeline = ValidateConfigPipeline(missing, NoOpLogger, ConfigFormatRegistry(listOf(YamlFormatModule())))
                val error = assertFailsWith<ConfigException> { pipeline.loadConfigBootstrap() }
                assertTrue(error.message.orEmpty().startsWith("[E0005] Config file not found: $missing"), error.message)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }
}
