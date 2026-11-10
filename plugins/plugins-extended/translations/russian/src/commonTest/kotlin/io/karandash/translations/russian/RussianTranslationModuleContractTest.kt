package io.karandash.translations.russian

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

class RussianTranslationModuleContractTest : ModuleContractTest<RussianTranslationModule>() {
    override fun createModule(): RussianTranslationModule = RussianTranslationModule()

    override suspend fun invokePrimaryWithEmptyInput(module: RussianTranslationModule) {
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
    fun `the pack is Russian with the east Slavic plural rule`() {
        val module = createModule()
        assertEquals(ModuleType.TRANSLATION, module.type)
        assertEquals("ru", module.language)
        assertEquals(PluralRule.EAST_SLAVIC, module.pluralRule)
        assertEquals(Version.VERSION, module.version)
        assertEquals("Как исправить", module.translations()["How to fix"])
    }

    @Test
    fun `register makes Russian a report language`() {
        RussianTranslationModule.register()
        assertEquals("Не пройдено", Translations.translate("Failed", "ru-RU"))
        val errors = Translations.translate("%d error|%d errors|%d errors", "ru").orEmpty()
        assertEquals(
            listOf("1 ошибка", "2 ошибки", "5 ошибок", "11 ошибок", "21 ошибка", "22 ошибки"),
            listOf(1, 2, 5, 11, 21, 22).map { PluralRule.EAST_SLAVIC.count(errors, it) },
        )
    }

    @Test
    fun `every counted text keeps three forms and every placeholder of its English template`() {
        val placeholder = Regex("""\{\d+\}|%[dsm1-3]""")
        RussianTranslationModule().translations().forEach { (english, russian) ->
            assertEquals(english.count { it == '|' }, russian.count { it == '|' }, english)
            val expected = placeholder.findAll(english).map { it.value }.toSet()
            assertTrue(placeholder.findAll(russian).map { it.value }.toSet() == expected, english)
        }
    }
}
