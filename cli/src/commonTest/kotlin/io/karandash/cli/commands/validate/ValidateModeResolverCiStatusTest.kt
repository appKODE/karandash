package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CiStatusSource
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.models.CiJobStatus
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** a CI status source (Jenkins) fills the CI status of a fetched MR whose platform reported none. */
class ValidateModeResolverCiStatusTest {
    private class FakeJenkins :
        PlatformServiceModule by ValidateModeResolverIdsTest.FakeHub,
        CiStatusSource {
        override val id: String = "fakejenkins"

        override suspend fun fetchCiStatus(env: (String) -> String?): String? = if (env("BUILD_URL") != null) "failed" else null
    }

    private suspend fun resolve(env: Map<String, String>) =
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
            ciEnv = { env[it] },
        ).resolve(
            ModuleRegistryImpl().apply {
                register(ValidateModeResolverIdsTest.FakeHub)
                register(FakeJenkins())
            },
            emptySet(),
        )

    @Test
    fun `jenkins status fills ci status and jobs when the platform returned none`() =
        runTest {
            val mr = resolve(mapOf("BUILD_URL" to "https://j/job/a/1/", "JOB_NAME" to "a")).mr
            assertEquals("failed", mr.ciStatus)
            assertEquals(listOf(CiJobStatus("a", "failed")), mr.ciJobs)
        }

    @Test
    fun `outside jenkins the ci status stays empty`() =
        runTest {
            assertNull(resolve(emptyMap()).mr.ciStatus)
        }
}
