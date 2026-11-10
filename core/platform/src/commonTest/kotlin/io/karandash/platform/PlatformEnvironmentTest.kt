package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformEnvironmentTest {
    @Test
    fun `names lists the variables of this process without values`() {
        val names = PlatformEnvironment.names()

        assertTrue("PATH" in names || "Path" in names, names.toString())
        assertTrue(names.none { it.isEmpty() || '=' in it }, names.toString())
        assertTrue(names.all { PlatformEnvironment.getEnv(it) != null }, names.toString())
    }
}
