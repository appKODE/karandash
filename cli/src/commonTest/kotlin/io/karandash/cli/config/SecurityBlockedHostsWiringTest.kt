package io.karandash.cli.config

import io.karandash.api.ModuleRegistryImpl
import io.karandash.cli.commands.validate.ValidateConfigPipeline
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.http.SsrfUrlValidator
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `security.blocked-hosts` reaches the run's SSRF validator. */
class SecurityBlockedHostsWiringTest {
    @AfterTest
    fun reset() = SsrfUrlValidator.configureAdditionalBlockedHosts(emptySet())

    @Test
    fun `security blocked-hosts is applied to the SSRF validator`() =
        runTest {
            val yaml = "security:\n  blocked-hosts:\n    - Proxy.Corp.Example.com\n"
            val pipeline = ValidateConfigPipeline(
                configPathStr = "config.yaml",
                logger = NoOpLogger,
                formatRegistry = ConfigFormatRegistry(listOf(YamlFormatModule())),
            )
            val result = pipeline.parseMerged(YamlFormatModule().parseToJsonElement(yaml), ModuleRegistryImpl())

            assertEquals(listOf("Proxy.Corp.Example.com"), result.config.security.blockedHosts)
            assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.validate("https://proxy.corp.example.com/hook") }
        }
}
