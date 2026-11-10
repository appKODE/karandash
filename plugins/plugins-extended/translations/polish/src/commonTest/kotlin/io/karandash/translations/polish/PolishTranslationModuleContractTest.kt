package io.karandash.translations.polish

import io.karandash.Version
import io.karandash.api.ModuleType
import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.Translations
import io.karandash.sdk.ModuleContractTest
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolishTranslationModuleContractTest : ModuleContractTest<PolishTranslationModule>() {
    override fun createModule(): PolishTranslationModule = PolishTranslationModule()

    override suspend fun invokePrimaryWithEmptyInput(module: PolishTranslationModule) {
        module.translations()
    }

    @AfterTest
    fun cleanup() {
        Translations.clear()
    }

    @Test
    fun `initialize with valid config returns true`() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun `initialize with malformed config returns false`() = runTest { verifyInitializeWithMalformedConfig() }

    @Test
    fun `shutdown without initialize does not throw`() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun `shutdown is idempotent`() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun `shutdown after initialize does not throw`() = runTest { verifyShutdownAfterInitialize() }

    @Test
    fun `primary method with empty input does not throw`() = runTest { verifyPrimaryMethodWithEmptyInput() }

    @Test
    fun `the pack is Polish with the west Slavic plural rule`() {
        val module = createModule()
        assertEquals(ModuleType.TRANSLATION, module.type)
        assertEquals("pl", module.language)
        assertEquals(PluralRule.WEST_SLAVIC, module.pluralRule)
        assertEquals(Version.VERSION, module.version)
        assertEquals("Jak naprawić", module.translations()["How to fix"])
    }

    @Test
    fun `register makes Polish a report language`() {
        PolishTranslationModule.register()
        assertEquals("Niezaliczone", Translations.text("Failed", "pl-PL"))
        assertEquals("2 błędy", PluralRule.WEST_SLAVIC.count(Translations.translate("%d error|%d errors|%d errors", "pl").orEmpty(), 2))
    }

    @Test
    fun `every counted text keeps three forms and every placeholder of its English template`() {
        val placeholder = Regex("""\{\d+\}|%[dsm1-3]""")
        PolishTranslationModule().translations().forEach { (english, polish) ->
            assertEquals(english.count { it == '|' }, polish.count { it == '|' }, english)
            val expected = placeholder.findAll(english).map { it.value }.toSet()
            assertTrue(placeholder.findAll(polish).map { it.value }.toSet() == expected, english)
        }
    }
}
