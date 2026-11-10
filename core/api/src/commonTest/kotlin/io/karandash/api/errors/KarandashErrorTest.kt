package io.karandash.api.errors

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KarandashErrorTest {
    private val placeholder = Regex("""\{(\w+)}""")

    @Test
    fun `codes are unique well formed and resolvable`() {
        val codes = KarandashError.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
        KarandashError.entries.forEach { error ->
            assertTrue(Regex("""E\d{4}""").matches(error.code), error.code)
            assertEquals("[${error.code}]", error.bracketCode)
            assertSame(error, KarandashError.fromCode(error.code))
            assertTrue(error.title.isNotBlank(), error.name)
            error.docsUrl?.let { assertTrue(it.endsWith(error.code.lowercase()), it) }
        }
        assertNull(KarandashError.fromCode("E9999"))
    }

    @Test
    fun `format fills every placeholder and leaves unknown ones`() {
        KarandashError.entries.forEach { error ->
            val names = placeholder.findAll(error.messageTemplate).map { it.groupValues[1] }.toSet()
            val formatted = error.format(names.associateWith { "<$it>" })

            assertTrue(placeholder.findAll(formatted).none(), formatted)
            names.forEach { assertTrue(formatted.contains("<$it>"), formatted) }
            assertEquals(error.messageTemplate, error.format(emptyMap()))
            assertEquals(formatted, ErrorCatalog.format(error.toErrorCode(), names.associateWith { "<$it>" }))
        }
    }

    @Test
    fun `catalog mirrors the enum by code and category`() {
        assertEquals(KarandashError.entries.size, ErrorCatalog.count())
        assertEquals(KarandashError.entries.map { it.toErrorCode() }, ErrorCatalog.all())
        ErrorCategory.entries.forEach { category ->
            val expected = KarandashError.entries.filter { it.category == category }
            assertEquals(expected, KarandashError.byCategory(category))
            assertEquals(expected.map { it.toErrorCode() }, ErrorCatalog.byCategory(category))
        }
        val first = KarandashError.entries.first()
        assertEquals(first.toErrorCode(), ErrorCatalog.lookup(first.code))
        assertNull(ErrorCatalog.lookup("nope"))
    }

    @Test
    fun `error code data survives a json round trip`() {
        KarandashError.entries.forEach { error ->
            val code = error.toErrorCode()
            val json = KarandashJson.Default.encodeToString(ErrorCode.serializer(), code)
            assertEquals(code, KarandashJson.Default.decodeFromString(ErrorCode.serializer(), json))
            assertEquals(error.severity, code.severity)
            assertEquals(error.suggestion, code.suggestion)
        }
    }

    @Test
    fun `config exceptions carry message cause and bracketed code`() {
        val cause = IllegalStateException("root")
        val chain = ConfigChainException(KarandashError.entries.first(), "bad chain")

        assertEquals("${KarandashError.entries.first().bracketCode} bad chain", chain.message)
        assertSame(KarandashError.entries.first(), chain.error)
        assertIs<ConfigException>(chain)
        assertSame(cause, ConfigException("m", cause).cause)
        assertEquals("missing", MissingPluginException("missing").message)
        assertSame(cause, ProviderException("p", cause).cause)
        assertNull(ProviderException("p").cause)
    }
}
