package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A `--pr` URL hands the provider initializer the base URL its host implies. */
class ValidateModeResolverPrBaseUrlTest {
    @Test
    fun `pr url base url reaches the provider initializer`() =
        runTest {
            val seen = mutableListOf<String?>()
            ValidateModeResolver(
                mrDataPath = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = "https://fakehub.test/o/r/pull/1",
                noAutoDetect = true,
                logger = NoOpLogger,
                readMrFileContent = { error("file mode not used") },
                providerRuntime = ProviderRuntime(ProviderResolver()),
                providerInitializer = { _, baseUrl -> seen += baseUrl },
            ).resolve(ModuleRegistryImpl().apply { register(ValidateModeResolverIdsTest.FakeHub) }, emptySet())
            assertEquals(listOf<String?>("https://fakehub.test"), seen)
        }
}
