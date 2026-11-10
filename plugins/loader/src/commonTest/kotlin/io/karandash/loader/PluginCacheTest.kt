package io.karandash.loader

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginCacheTest {
    private class MockModule(
        override val id: String,
        override val name: String = "Mock",
        override val version: String = "1.0",
        override val type: ModuleType = ModuleType.RULE
    ) : Module {
        var shutdownCalled = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun shutdown() {
            shutdownCalled = true
        }
    }

    private fun createTestModule(
        id: String,
        name: String = "Test"
    ): Module =
        object : Module {
            override val id: String = id
            override val name: String = name
            override val version: String = "1.0.0"
            override val type: ModuleType = ModuleType.RULE

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    private class MockCloseable : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `put and get module`() {
        val cache = PluginCache()
        val module = createTestModule("test.plugin")
        val resource = MockCloseable()

        cache.put(module, resource, "/path/to/test.jar")

        assertEquals(module, cache.get("test.plugin"))
        assertEquals("/path/to/test.jar", cache.sourcePath("test.plugin"))
        assertTrue(cache.contains("test.plugin"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `get returns null for missing id`() {
        val cache = PluginCache()

        assertNull(cache.get("nonexistent"))
        assertNull(cache.sourcePath("nonexistent"))
        assertFalse(cache.contains("nonexistent"))
    }

    @Test
    fun `remove returns true for existing module`() =
        runTest {
            val cache = PluginCache()
            val module = createTestModule("test.plugin")
            val resource = MockCloseable()
            cache.put(module, resource, "/test.jar")

            assertTrue(cache.remove("test.plugin"))
            assertFalse(cache.contains("test.plugin"))
            assertEquals(0, cache.size())
            assertTrue(resource.closed)
        }

    @Test
    fun `remove returns false for missing id`() =
        runTest {
            val cache = PluginCache()

            assertFalse(cache.remove("nonexistent"))
        }

    @Test
    fun `ids returns all cached ids`() {
        val cache = PluginCache()
        cache.put(createTestModule("a"), MockCloseable(), "/a.jar")
        cache.put(createTestModule("b"), MockCloseable(), "/b.jar")

        assertEquals(setOf("a", "b"), cache.ids())
    }

    @Test
    fun `modules returns all cached modules`() {
        val cache = PluginCache()
        val m1 = createTestModule("a", "Module A")
        val m2 = createTestModule("b", "Module B")
        cache.put(m1, MockCloseable(), "/a.jar")
        cache.put(m2, MockCloseable(), "/b.jar")

        val modules = cache.modules()
        assertEquals(2, modules.size)
        assertNotNull(modules.find { it.id == "a" })
        assertNotNull(modules.find { it.id == "b" })
    }

    @Test
    fun `clear removes all entries`() =
        runTest {
            val cache = PluginCache()
            val r1 = MockCloseable()
            val r2 = MockCloseable()
            cache.put(createTestModule("a"), r1, "/a.jar")
            cache.put(createTestModule("b"), r2, "/b.jar")

            cache.clear()

            assertEquals(0, cache.size())
            assertTrue(cache.ids().isEmpty())
            assertTrue(r1.closed)
            assertTrue(r2.closed)
        }

    @Test
    fun `put same ID twice closes old resource before replacing`() =
        runTest {
            val cache = PluginCache()
            val closeCalls = mutableListOf<String>()

            val resource1 = object : AutoCloseable {
                override fun close() {
                    closeCalls.add("resource1")
                }
            }
            val resource2 = object : AutoCloseable {
                override fun close() {
                    closeCalls.add("resource2")
                }
            }

            val module1 = MockModule("test-id")
            val module2 = MockModule("test-id") // Same ID

            cache.put(module1, resource1, "/path1.jar")
            assertEquals(0, closeCalls.size, "resource1 should not be closed yet")

            cache.put(module2, resource2, "/path2.jar") // Overwrites
            assertEquals(1, closeCalls.size, "resource1 should be closed on overwrite")
            assertEquals("resource1", closeCalls[0])

            cache.remove("test-id")
            assertEquals(2, closeCalls.size, "resource2 should be closed on remove")
            assertEquals("resource2", closeCalls[1])
        }

    @Test
    fun `put with null resource then remove does not NPE`() =
        runTest {
            val cache = PluginCache()
            val module = MockModule("null-resource-test")
            cache.put(module, null, "/path.jar")

            // Should not throw
            cache.remove("null-resource-test")
        }

    @Test
    fun `remove calls module shutdown`() =
        runTest {
            val cache = PluginCache()
            val module = MockModule("shutdown-test")
            cache.put(module, MockCloseable(), "/path.jar")

            assertFalse(module.shutdownCalled, "shutdown not yet called")
            cache.remove("shutdown-test")
            assertTrue(module.shutdownCalled, "shutdown should be called on remove")
        }
}
