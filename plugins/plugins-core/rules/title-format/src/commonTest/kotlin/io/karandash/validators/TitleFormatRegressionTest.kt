package io.karandash.validators

import io.karandash.utils.ConventionalTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** one shared default list of conventional types. */
class TitleFormatRegressionTest {
    @Test
    fun `title format default types are the shared conventional list`() {
        assertEquals(ConventionalTypes.DEFAULT, TitleFormatConfig.DEFAULT_CONVENTIONAL_TYPES)
    }

    @Test
    fun `structured title pattern accepts ci build and revert`() {
        val pattern = Regex(checkNotNull(TitleFormatConfig.buildStructuredPattern(TitleFormatConfig(type = TitleTypeConfig()))))
        assertTrue(listOf("ci: x", "build: x", "revert: x").all { pattern.matches(it) })
    }
}
