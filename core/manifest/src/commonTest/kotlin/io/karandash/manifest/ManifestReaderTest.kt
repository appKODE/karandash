package io.karandash.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestReaderTest {
    @Test
    fun `parsePluginDeclarations returns empty list for null`() {
        assertEquals(emptyList(), ManifestReader.parsePluginDeclarations(null))
    }

    @Test
    fun `parsePluginDeclarations returns empty list for non-list`() {
        assertEquals(emptyList(), ManifestReader.parsePluginDeclarations("not-a-list"))
    }

    @Test
    fun `parsePluginDeclarations handles string shorthand`() {
        val result = ManifestReader.parsePluginDeclarations(listOf("com.example.my-rule"))
        assertEquals(1, result.size)
        assertEquals("com.example.my-rule", result[0].id)
        assertNull(result[0].version)
        assertNull(result[0].source)
    }

    @Test
    fun `parsePluginDeclarations handles map form with version`() {
        val raw = listOf(mapOf("id" to "com.example.plugin", "version" to "1.2.0"))
        val result = ManifestReader.parsePluginDeclarations(raw)
        assertEquals(1, result.size)
        assertEquals("com.example.plugin", result[0].id)
        assertEquals("1.2.0", result[0].version)
    }

    @Test
    fun `parsePluginDeclarations handles map form with version null`() {
        val raw = listOf(mapOf("id" to "com.example.plugin"))
        val result = ManifestReader.parsePluginDeclarations(raw)
        assertEquals(1, result.size)
        assertNull(result[0].version)
    }

    @Test
    fun `parsePluginDeclarations handles map form with source override`() {
        val raw = listOf(mapOf("id" to "com.example.plugin", "source" to "https://example.com/plugin.jar"))
        val result = ManifestReader.parsePluginDeclarations(raw)
        assertEquals(1, result.size)
        assertEquals("https://example.com/plugin.jar", result[0].source)
    }

    @Test
    fun `parsePluginDeclarations skips map entries without id`() {
        val raw = listOf(mapOf("version" to "1.0.0"))
        val result = ManifestReader.parsePluginDeclarations(raw)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `parsePluginDeclarations handles mixed string and map entries`() {
        val raw = listOf(
            "com.example.plugin-a",
            mapOf("id" to "com.example.plugin-b", "version" to "2.0.0")
        )
        val result = ManifestReader.parsePluginDeclarations(raw)
        assertEquals(2, result.size)
        assertEquals("com.example.plugin-a", result[0].id)
        assertNull(result[0].version)
        assertEquals("com.example.plugin-b", result[1].id)
        assertEquals("2.0.0", result[1].version)
    }

    @Test
    fun `parsePluginDeclarations skips blank strings`() {
        val result = ManifestReader.parsePluginDeclarations(listOf("", "  ", "valid-id"))
        assertEquals(1, result.size)
        assertEquals("valid-id", result[0].id)
    }

    @Test
    fun `PluginDeclaration version null means latest at install time`() {
        val decl = PluginDeclaration(id = "com.example.plugin")
        assertNull(decl.version, "version=null should mean latest at install time")
    }
}
