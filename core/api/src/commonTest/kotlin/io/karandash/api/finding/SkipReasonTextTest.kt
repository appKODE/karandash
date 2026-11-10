package io.karandash.api.finding

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SkipReasonTextTest {
    @BeforeTest
    fun registerPack() = TestZzPack.register()

    @Test
    fun `a reason without a pack stays as given`() {
        assertEquals("gated: trivial diff: lockfile", skipReasonIn("gated: trivial diff: lockfile", "en"))
        assertEquals("dry run", skipReasonIn("dry run", null))
    }

    @Test
    fun `a fixed reason and its nested reasons are translated`() {
        assertEquals("DRY", skipReasonIn("dry run", LANGUAGE))
        assertEquals("GATED[TRIVIAL[LOCK]]", skipReasonIn("gated: trivial diff: lockfile", LANGUAGE))
        assertEquals("PROVIDER[TIMEOUT]", skipReasonIn("provider error: timed out", LANGUAGE))
        assertEquals("FILES[120>50]", skipReasonIn("120 files exceeds maxFiles=50", LANGUAGE))
    }

    @Test
    fun `semicolon and comma lists are translated piece by piece`() {
        assertEquals("DRY; TIMEOUT", skipReasonIn("dry run; timed out", LANGUAGE))
        assertEquals("TRIVIAL[LOCK, GEN]", skipReasonIn("trivial diff: lockfile, generated", LANGUAGE))
    }

    @Test
    fun `a free-form reason or a template without its pack entry passes through`() {
        assertEquals("the moon is full", skipReasonIn("the moon is full", LANGUAGE))
        assertEquals("canary: shadow mode", skipReasonIn("canary: shadow mode", LANGUAGE))
        assertEquals("gated: ", skipReasonIn("gated: ", LANGUAGE))
        assertEquals("prefix gated: x", skipReasonIn("prefix gated: x", LANGUAGE))
    }

    private companion object {
        const val LANGUAGE = TestZzPack.LANGUAGE
    }
}
