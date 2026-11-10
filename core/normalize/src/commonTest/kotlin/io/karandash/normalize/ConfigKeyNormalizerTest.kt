package io.karandash.normalize

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigKeyNormalizerTest {
    // kebabToCamelCase

    @Test
    fun alreadyCamelCasePassesThrough() {
        assertEquals("titleFormat", ConfigKeyNormalizer.kebabToCamelCase("titleFormat"))
    }

    @Test
    fun kebabCaseConvertsCorrectly() {
        assertEquals("titleFormat", ConfigKeyNormalizer.kebabToCamelCase("title-format"))
    }

    @Test
    fun multipleDashesConvertCorrectly() {
        assertEquals("crossEntityConsistency", ConfigKeyNormalizer.kebabToCamelCase("cross-entity-consistency"))
    }

    @Test
    fun singleCharacterSegmentsConvertCorrectly() {
        assertEquals("aB", ConfigKeyNormalizer.kebabToCamelCase("a-b"))
    }

    @Test
    fun emptyStringReturnsEmptyString() {
        assertEquals("", ConfigKeyNormalizer.kebabToCamelCase(""))
    }

    @Test
    fun stringWithNoDashesReturnsUnchanged() {
        assertEquals("plain", ConfigKeyNormalizer.kebabToCamelCase("plain"))
    }

    // camelToKebabCase

    @Test
    fun camelCaseConvertsToKebabCase() {
        assertEquals("title-format", ConfigKeyNormalizer.camelToKebabCase("titleFormat"))
    }

    @Test
    fun multipleUppercaseConvertsToMultipleDashes() {
        assertEquals("cross-entity-consistency", ConfigKeyNormalizer.camelToKebabCase("crossEntityConsistency"))
    }

    @Test
    fun alreadyLowercaseReturnsUnchanged() {
        assertEquals("plain", ConfigKeyNormalizer.camelToKebabCase("plain"))
    }

    @Test
    fun emptyStringReturnsEmptyStringForCamelToKebab() {
        assertEquals("", ConfigKeyNormalizer.camelToKebabCase(""))
    }

    @Test
    fun roundTripKebabToCamelAndBack() {
        val original = "title-format"
        val camel = ConfigKeyNormalizer.kebabToCamelCase(original)
        assertEquals(original, ConfigKeyNormalizer.camelToKebabCase(camel))
    }

    // ── Consecutive and mixed separator edge cases ──────────────────────

    @Test
    fun consecutiveDashesProduceSingleCamelCaseTransition() {
        assertEquals("myKey", ConfigKeyNormalizer.kebabToCamelCase("my--key"))
    }

    @Test
    fun consecutiveUnderscoresProduceSingleCamelCaseTransition() {
        assertEquals("myKey", ConfigKeyNormalizer.kebabToCamelCase("my__key"))
    }

    @Test
    fun mixedSeparatorsDashThenUnderscore() {
        assertEquals("myKey", ConfigKeyNormalizer.kebabToCamelCase("my-_key"))
    }

    @Test
    fun mixedSeparatorsUnderscoreThenDash() {
        assertEquals("myKey", ConfigKeyNormalizer.kebabToCamelCase("my_-key"))
    }

    @Test
    fun screamingSnakeCasePreservedExceptAfterSeparator() {
        // MY_KEY: M,Y kept as-is, _ sets capitalizeNext, K uppercased (already upper), E,Y kept as-is
        assertEquals("MYKEY", ConfigKeyNormalizer.kebabToCamelCase("MY_KEY"))
    }

    @Test
    fun leadingSeparatorsDroppedAndNextCharCapitalized() {
        assertEquals("Key", ConfigKeyNormalizer.kebabToCamelCase("--key"))
    }

    @Test
    fun trailingSeparatorsIgnored() {
        assertEquals("key", ConfigKeyNormalizer.kebabToCamelCase("key--"))
    }

    @Test
    fun singleDashProducesEmptyString() {
        assertEquals("", ConfigKeyNormalizer.kebabToCamelCase("-"))
    }

    @Test
    fun singleUnderscoreProducesEmptyString() {
        assertEquals("", ConfigKeyNormalizer.kebabToCamelCase("_"))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = ConfigKeyNormalizer.kebabToCamelCase("my-key")
        val twice = ConfigKeyNormalizer.kebabToCamelCase(once)
        assertEquals(once, twice)
    }
}
