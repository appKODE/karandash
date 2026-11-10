package io.karandash.translations.russian

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pack against `former-builtin-ru.json`: every `en`/`ru` pair of the text enums as it was built in before the pack
 * took Russian over (dumped from the sources at the move). A pair listed in [CORRECTED] was changed on purpose.
 */
class FormerBuiltInRussianTest {
    @Test
    fun `the pack keeps every former built-in Russian text`() {
        val root = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }
        val fixture = TestFiles.readText(Path(root, FIXTURE))
        val pairs = KarandashJson.Default
            .parseToJsonElement(fixture)
            .jsonArray
            .map { it.jsonObject }
        assertTrue(pairs.size > 180, "fixture lost entries: ${pairs.size}")
        val mismatches = pairs.mapNotNull { pair ->
            val en = checkNotNull(pair["en"]).jsonPrimitive.content
            val expected = CORRECTED[en] ?: checkNotNull(pair["ru"]).jsonPrimitive.content
            val actual = RUSSIAN_TEXTS[en]
            if (actual == expected) null else "${pair["enum"]}.${pair["entry"]}: expected <$expected>, was <$actual>"
        }
        assertEquals(emptyList(), mismatches)
    }

    private companion object {
        const val FIXTURE = "plugins/plugins-extended/translations/russian/src/commonTest/resources/former-builtin-ru.json"

        /** English template to its corrected Russian text: wording fixed after the move. */
        val CORRECTED: Map<String, String> = emptyMap()
    }
}
