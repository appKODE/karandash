package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.logger.NoOpLogger
import io.karandash.platform.currentWorkingDirectory
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigDiscoveryServiceTest {
    private val yaml = object : ConfigFormatModule {
        override val formatId = "yaml"
        override val fileExtensions = setOf("yaml", "yml")

        override suspend fun parseToJsonElement(content: String): JsonElement = JsonObject(emptyMap())
    }
    private val service = ConfigDiscoveryService(ConfigFormatRegistry(listOf(yaml)), NoOpLogger)

    @Test
    fun `explicit path is used as given and never falls back to zero-config`() {
        val result = service.discover("does/not/exist.yaml")
        assertEquals(Path("does/not/exist.yaml"), result.configFile)
        assertFalse(result.usingZeroConfig)
    }

    @Test
    fun `format is detected from the extension and unknown extensions give null`() {
        assertSame(yaml, service.detectFormat(Path("cfg/config.yml")))
        assertNull(service.detectFormat(Path("cfg/config.toml")))
    }

    @Test
    fun `exists is true only for a regular file`() {
        val dir = TestFiles.createTestTempDirectory("discovery")
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "config.yaml")
        SystemFileSystem.sink(file).buffered().use { it.writeString("rules: {}") }

        assertTrue(service.exists(file))
        assertFalse(service.exists(dir))
        assertFalse(service.exists(Path(dir, "missing.yaml")))
        SystemFileSystem.delete(file)
        SystemFileSystem.delete(dir)
    }

    @Test
    fun `KARANDASH_CONFIG_DIR moves the discovery start directory`() {
        val dir = TestFiles.createTestTempDirectory("discovery-env")
        val configDir = Path(dir, ".karandash")
        SystemFileSystem.createDirectories(configDir)
        val file = Path(configDir, "config.yaml")
        SystemFileSystem.sink(file).buffered().use { it.writeString("rules: {}") }
        try {
            val env = mapOf(ConfigDiscovery.CONFIG_DIR_ENV to " $dir ")
            assertEquals(dir, ConfigDiscovery.defaultStartDir(env::get))
            val found = ConfigDiscovery(ConfigFormatRegistry(listOf(yaml))).discover(ConfigDiscovery.defaultStartDir(env::get))
            assertEquals("config.yaml", found.file?.name)
            assertEquals(currentWorkingDirectory(), ConfigDiscovery.defaultStartDir { " " })
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(configDir)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `several config files in one directory warn which one wins`() {
        val dir = TestFiles.createTestTempDirectory("discovery-shadow")
        val configDir = Path(dir, ".karandash")
        SystemFileSystem.createDirectories(configDir)
        val files = listOf(Path(configDir, "config.yaml"), Path(configDir, "config.yml"))
        files.forEach { file -> SystemFileSystem.sink(file).buffered().use { it.writeString("rules: {}") } }
        val warnings = mutableListOf<String>()
        val logger = object : io.karandash.logger.Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        try {
            val found = ConfigDiscovery(ConfigFormatRegistry(listOf(yaml)), logger).discover(dir)
            val warning = warnings.single()
            assertTrue("using ${found.matchedPattern}" in warning && "ignoring" in warning, warning)
        } finally {
            files.forEach(SystemFileSystem::delete)
            SystemFileSystem.delete(configDir)
            SystemFileSystem.delete(dir)
        }
    }
}
