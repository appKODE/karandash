package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** `~` expands to `$HOME` when set, the JVM `user.home` otherwise. */
class UserHomeJvmTest {
    @Test
    fun `HOME wins over user home`() {
        assertEquals("/env/home", userHome("/env/home", "/jvm/home"))
    }

    @Test
    fun `unset or blank HOME falls back to user home`() {
        assertEquals("/jvm/home", userHome(null, "/jvm/home"))
        assertEquals("/jvm/home", userHome(" ", "/jvm/home"))
    }

    @Test
    fun `home directory follows the HOME variable of this process`() {
        val expected = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        assertEquals(expected, getUserHomeDirectoryString())
    }
}
