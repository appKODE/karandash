package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewUnitsTest {
    @Test
    fun `a file belongs to its module root or its directory with tests next to sources`() {
        assertEquals("app", ReviewUnits.of("app/src/main/kotlin/A.kt"))
        assertEquals("app", ReviewUnits.of("app/src/androidTest/kotlin/AUiTest.kt"))
        assertEquals("core/net", ReviewUnits.of("core/net/src/test/NetTest.kt"))
        assertEquals(".", ReviewUnits.of("src/main/App.kt"))
        assertEquals("base", ReviewUnits.of("base/string_utils.cc"))
        assertEquals("base", ReviewUnits.of("base/tests/string_utils_test.cc"))
        assertEquals(".", ReviewUnits.of("README.md"))
    }

    @Test
    fun `units are ranked by the riskiest file then by lines changed`() {
        val units = ReviewUnits.group(
            listOf(
                change("docs/guide.md", 12),
                change("web/src/App.kt", 10),
                change("api/src/auth/Login.kt", 1),
                change("web/src/test/AppTest.kt", 5),
            ),
        )
        assertEquals(listOf("api", "web", "docs"), units.map { it.name })
        assertEquals(listOf("web/src/App.kt", "web/src/test/AppTest.kt"), units[1].changes.map { it.path })
    }

    @Test
    fun `test paths cover Android and C++ layouts`() {
        assertTrue(ReviewUnits.isTest("app/src/test/kotlin/A.kt"))
        assertTrue(ReviewUnits.isTest("app/src/androidTest/kotlin/A.kt"))
        assertTrue(ReviewUnits.isTest("base/string_utils_test.cc"))
        assertTrue(ReviewUnits.isTest("tests/integration/run.cc"))
        assertFalse(ReviewUnits.isTest("app/src/main/kotlin/Latest.kt"))
        assertFalse(ReviewUnits.isTest("base/contest.cc"))
    }

    private fun units(vararg paths: String) =
        ReviewUnits
            .group(paths.map { change(it, 1) })
            .map { u ->
                u.name to
                    u.changes.map { it.path }.toSet()
            }.toSet()

    @Test
    fun `a Python test under a root tests directory pairs with its module`() {
        assertEquals(setOf("pkg" to setOf("pkg/mod.py", "tests/test_mod.py")), units("pkg/mod.py", "tests/test_mod.py"))
        assertEquals("pkg", ReviewUnits.of("tests/pkg/test_mod.py"))
    }

    @Test
    fun `a Dart test directory pairs with lib at the same parent`() {
        assertEquals(setOf("." to setOf("lib/a.dart", "test/a_test.dart")), units("lib/a.dart", "test/a_test.dart"))
        assertEquals("pkgs/ui", ReviewUnits.of("pkgs/ui/lib/src/w.dart"))
        assertEquals("pkgs/ui", ReviewUnits.of("pkgs/ui/test/w_test.dart"))
    }

    @Test
    fun `a C++ test pairs with its source by name and Gradle layouts keep their module`() {
        assertEquals(setOf("base" to setOf("base/foo.cc", "base/test/foo_test.cc")), units("base/foo.cc", "base/test/foo_test.cc"))
        assertEquals(
            setOf("app" to setOf("app/src/main/kotlin/A.kt", "app/src/test/kotlin/ATest.kt", "app/src/androidTest/kotlin/AUiTest.kt")),
            units("app/src/main/kotlin/A.kt", "app/src/test/kotlin/ATest.kt", "app/src/androidTest/kotlin/AUiTest.kt"),
        )
    }

    @Test
    fun `a flat repo counts one unit per paired module not one per top-level directory`() {
        assertEquals(2, units("alpha/a.py", "beta/b.py", "tests/test_a.py", "tests/test_b.py").size)
    }

    @Test
    fun `a test that matches no changed source keeps its own unit`() {
        assertEquals(setOf("pkg" to setOf("pkg/mod.py"), "." to setOf("tests/test_other.py")), units("pkg/mod.py", "tests/test_other.py"))
    }

    private fun change(
        path: String,
        lines: Int,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = lines, diffContent = "+x")
}
