package io.karandash.cli.errors

import io.karandash.api.errors.ErrorCatalog
import io.karandash.api.errors.ErrorCategory
import io.karandash.api.errors.KarandashError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorCatalogTest {
    @Test
    fun `lookup existing error code returns ErrorCode`() {
        val error = ErrorCatalog.lookup("E0001")
        assertNotNull(error)
        assertEquals("E0001", error.code)
        assertEquals("Unclosed string literal", error.title)
        assertEquals(ErrorCategory.CONFIG, error.category)
    }

    @Test
    fun `lookup non-existing error code returns null`() {
        val error = ErrorCatalog.lookup("E9999")
        assertNull(error)
    }

    @Test
    fun `byCategory returns all CONFIG errors`() {
        val configErrors = ErrorCatalog.byCategory(ErrorCategory.CONFIG)
        assertEquals(24, configErrors.size) // Updated: 23 previous + 1 (E0025)
        assertTrue(configErrors.all { it.category == ErrorCategory.CONFIG })
        assertTrue(configErrors.any { it.code == "E0001" })
        assertTrue(configErrors.any { it.code == "E0002" })
        assertTrue(configErrors.any { it.code == "E0003" })
        assertTrue(configErrors.any { it.code == "E0004" })
        assertTrue(configErrors.any { it.code == "E0005" })
        assertTrue(configErrors.any { it.code == "E0006" })
        assertTrue(configErrors.any { it.code == "E0007" })
        assertTrue(configErrors.any { it.code == "E0008" })
        assertTrue(configErrors.any { it.code == "E0009" })
        assertTrue(configErrors.any { it.code == "E0010" })
        assertTrue(configErrors.any { it.code == "E0011" })
        assertTrue(configErrors.any { it.code == "E0013" }, "E0013 should be a config error")
        assertTrue(configErrors.any { it.code == "E0014" }, "E0014 should be a config error")
        assertTrue(configErrors.any { it.code == "E0015" }, "E0015 should be a config error")
        assertTrue(configErrors.any { it.code == "E0016" }, "E0016 should be a config error")
        assertTrue(configErrors.any { it.code == "E0017" }, "E0017 should be a config error")
        assertTrue(configErrors.any { it.code == "E0018" }, "E0018 should be a config error")
        assertTrue(configErrors.any { it.code == "E0019" }, "E0019 should be a config error")
        assertTrue(configErrors.any { it.code == "E0020" }, "E0020 should be a config error")
        assertTrue(configErrors.any { it.code == "E0021" }, "E0021 should be a config error")
        assertTrue(configErrors.any { it.code == "E0022" }, "E0022 should be a config error")
        assertTrue(configErrors.any { it.code == "E0023" }, "E0023 should be a config error")
    }

    @Test
    fun `byCategory returns all PROVIDER errors`() {
        val providerErrors = ErrorCatalog.byCategory(ErrorCategory.PROVIDER)
        assertEquals(6, providerErrors.size) // Updated: 5 original + 1 new (E0012)
        assertTrue(providerErrors.all { it.category == ErrorCategory.PROVIDER })
        assertTrue(providerErrors.any { it.code == "E0012" })
        assertTrue(providerErrors.any { it.code == "E0100" })
        assertTrue(providerErrors.any { it.code == "E0101" })
        assertTrue(providerErrors.any { it.code == "E0102" })
        assertTrue(providerErrors.any { it.code == "E0103" })
        assertTrue(providerErrors.any { it.code == "E0104" })
    }

    @Test
    fun `byCategory returns all VALIDATION errors`() {
        val validationErrors = ErrorCatalog.byCategory(ErrorCategory.VALIDATION)
        assertEquals(5, validationErrors.size)
        assertTrue(validationErrors.all { it.category == ErrorCategory.VALIDATION })
        assertTrue(validationErrors.any { it.code == "E0200" })
        assertTrue(validationErrors.any { it.code == "E0201" })
        assertTrue(validationErrors.any { it.code == "E0202" })
        assertTrue(validationErrors.any { it.code == "E0203" })
        assertTrue(validationErrors.any { it.code == "E0204" })
    }

    @Test
    fun `byCategory returns all PLUGIN errors`() {
        val pluginErrors = ErrorCatalog.byCategory(ErrorCategory.PLUGIN)
        assertEquals(4, pluginErrors.size)
        assertTrue(pluginErrors.all { it.category == ErrorCategory.PLUGIN })
        assertTrue(pluginErrors.any { it.code == "E0300" })
        assertTrue(pluginErrors.any { it.code == "E0301" })
        assertTrue(pluginErrors.any { it.code == "E0302" })
        assertTrue(pluginErrors.any { it.code == "E0303" })
    }

    @Test
    fun `format substitutes single parameter`() {
        val error = ErrorCatalog.lookup("E0005")!!
        val formatted = ErrorCatalog.format(error, mapOf("path" to ".karandash/config.kts"))
        assertEquals("Configuration file not found at .karandash/config.kts.", formatted)
    }

    @Test
    fun `format substitutes multiple parameters`() {
        val error = ErrorCatalog.lookup("E0103")!!
        val formatted = ErrorCatalog.format(
            error,
            mapOf("mrId" to "123", "projectId" to "owner/repo")
        )
        assertEquals("Merge request 123 not found in project owner/repo.", formatted)
    }

    @Test
    fun `format handles missing parameters gracefully`() {
        val error = ErrorCatalog.lookup("E0002")!!
        // E0002 template: "Unknown rule '{rule}' in configuration."
        // Suggestion field has {suggestion} placeholder, but format() only operates on messageTemplate
        val formatted = ErrorCatalog.format(error, mapOf("rule" to "titleFormat"))
        // Should contain the substituted parameter
        assertTrue(formatted.contains("titleFormat"))
        assertTrue(formatted.contains("Unknown rule"))
        // Unmatched placeholders remain as-is in the output
        assertTrue(!formatted.contains("{rule}"))
    }

    @Test
    fun `all returns complete catalog`() {
        val allErrors = ErrorCatalog.all()
        assertEquals(39, allErrors.size) // Updated: 38 previous + 1 (E0025)
    }

    @Test
    fun `count returns correct total`() {
        assertEquals(39, ErrorCatalog.count()) // Updated: 38 previous + 1 (E0025)
    }

    @Test
    fun `all error codes have unique codes`() {
        val allCodes = ErrorCatalog.all().map { it.code }
        assertEquals(allCodes.size, allCodes.toSet().size, "Error codes should be unique")
    }

    @Test
    fun `all error codes have non-empty fields`() {
        ErrorCatalog.all().forEach { error ->
            assertTrue(error.code.isNotEmpty(), "Error code should not be empty")
            assertTrue(error.title.isNotEmpty(), "Error title should not be empty")
            assertTrue(error.messageTemplate.isNotEmpty(), "Message template should not be empty")
        }
    }

    @Test
    fun `all error codes have valid docs URLs`() {
        ErrorCatalog.all().forEach { error ->
            val docsUrl = error.docsUrl
            assertNotNull(docsUrl, "Error ${error.code} should have docs URL")
            assertEquals(
                "https://appkode.github.io/karandash/reference/error-codes/#${error.code.lowercase()}",
                docsUrl,
                "Docs URL of ${error.code} should be its anchor on the error-codes page",
            )
        }
    }

    @Test
    fun `plugin and cache hints name commands and paths that exist`() {
        val notInstalled = checkNotNull(KarandashError.PLUGIN_NOT_INSTALLED.suggestion)
        val unknown = checkNotNull(KarandashError.UNKNOWN_MODULE_REFERENCE.suggestion)
        assertTrue("karandash plugin install --registry" in notInstalled, notInstalled)
        assertTrue("karandash plugin install --registry" in unknown, unknown)
        val cache = checkNotNull(KarandashError.CACHE_OPERATION_FAILED.suggestion)
        assertTrue("~/.karandash/cache/ " in cache, cache)
    }
}
