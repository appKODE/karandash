package io.karandash.cli.commands.validate

import io.karandash.cache.CacheManager
import io.karandash.cli.commands.cache.expandCacheDir
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ValidateConfigPipelineCacheDirTest {
    private val pipeline = ValidateConfigPipeline(null, NoOpLogger, ConfigFormatRegistry(listOf(YamlFormatModule())))

    private fun root(dir: String) = JsonObject(mapOf("cache" to JsonObject(mapOf("dir" to JsonPrimitive(dir)))))

    @Test
    fun `remote extends are cached under the root config's cache dir`() {
        assertEquals(Path("ci-cache"), pipeline.rootCacheDir(root("ci-cache")))
    }

    @Test
    fun `no root cache dir or an uninterpolated one keeps the home default`() {
        val default = expandCacheDir(CacheManager.DEFAULT_BASE_DIR)
        assertEquals(default, pipeline.rootCacheDir(null))
        assertEquals(default, pipeline.rootCacheDir(JsonObject(emptyMap())))
        assertEquals(default, pipeline.rootCacheDir(root("\${CACHE_DIR}")))
    }
}
