package io.karandash.testfixtures

import io.karandash.platform.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

/** The `karandash.kmp-library` convention forwards `-Dkarandash.updateGolden/updateBaseline` to every test process. */
class TestEnvForwardingTest {
    @Test
    fun `the update golden and update baseline flags reach the test process`() {
        listOf(GoldenFiles.UPDATE_GOLDEN_ENV, GoldenFiles.UPDATE_BASELINE_ENV).forEach { name ->
            val value = PlatformEnvironment.getEnv(name)
            assertTrue(value == "true" || value == "false", "$name=$value")
        }
    }
}
