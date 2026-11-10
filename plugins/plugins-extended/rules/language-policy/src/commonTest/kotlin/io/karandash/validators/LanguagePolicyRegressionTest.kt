package io.karandash.validators

import kotlin.test.Test
import kotlin.test.assertEquals

/** Latin Extended Additional and Cyrillic Supplement belong to their scripts. */
class LanguagePolicyRegressionTest {
    @Test
    fun `vietnamese letters pass the latin script`() {
        val validator = LanguagePolicyValidator(LanguagePolicyConfig(allowedScripts = listOf("latin")))
        assertEquals(emptyList(), validator.findDisallowedLetters("Việt Nam ẞ"))
    }

    @Test
    fun `cyrillic supplement letters pass the cyrillic script`() {
        val validator = LanguagePolicyValidator(LanguagePolicyConfig(allowedScripts = listOf("cyrillic")))
        assertEquals(emptyList(), validator.findDisallowedLetters("ԀԐ"))
    }
}
