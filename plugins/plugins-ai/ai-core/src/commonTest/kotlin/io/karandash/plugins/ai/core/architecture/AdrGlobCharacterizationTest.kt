package io.karandash.plugins.ai.core.architecture

import io.karandash.utils.GlobMatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/** [AdrValidator.matchesGlob] delegates to [GlobMatcher], so both must agree on every case. */
class AdrGlobCharacterizationTest {
    @Test
    fun agreesWithGlobMatcher() {
        listOf(
            "src/main/Foo.kt" to "src/**/*.kt",
            "src/Foo.kt" to "src/*.kt",
            "a/b/c/Foo.kt" to "**/Foo.kt",
            "module/src/Foo.kt" to "src/*.kt",
            "src/Foo.kts" to "src/*.kt",
            "lib/[x/Foo.kt" to "[x",
        ).forEach { (path, pattern) ->
            assertEquals(GlobMatcher.matches(path, pattern), AdrValidator.matchesGlob(path, pattern), "$path ~ $pattern")
        }
    }
}
