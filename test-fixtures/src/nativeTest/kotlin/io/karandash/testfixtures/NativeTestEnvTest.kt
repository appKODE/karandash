package io.karandash.testfixtures

import io.karandash.platform.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native test processes must never see live-eval switches or provider credentials (blanked by `karandash.kmp-library`). */
class NativeTestEnvTest {
    @Test
    fun `live eval and provider tokens are blank in native tests`() {
        val leaked = listOf("KARANDASH_EVAL_LIVE", "GITLAB_TOKEN", "GITHUB_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")
            .filterNot { PlatformEnvironment.getEnv(it).isNullOrEmpty() }

        assertEquals(emptyList(), leaked)
    }
}
