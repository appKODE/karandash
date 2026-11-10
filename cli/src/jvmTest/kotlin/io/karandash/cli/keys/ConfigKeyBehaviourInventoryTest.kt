package io.karandash.cli.keys

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.HIDE_FIELDS_KEY
import io.karandash.cli.docs.DocsSupport
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.pipeline.EXTENDS_CACHE_KEYS
import io.karandash.config.pipeline.EXTENDS_ENTRY_KEYS
import io.karandash.config.pipeline.UnknownTopLevelKeys
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.sdk.typed.TypedModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every config key has a behaviour test: a test that sets the key and checks what it changes (a finding, an output,
 * a merge result, a request). The keys are collected from the code, not typed by hand: the [Config] serializer
 * (top-level sections and their nested keys), the `ai:` section ([AiPluginConfig]), `extends:` entry keys, the per-rule meta keys,
 * the key every format takes ([HIDE_FIELDS_KEY]) and, per built-in
 * module, its `configSchema()`, `defaultConfig()` and typed config serializer.
 *
 * The manifests in [MANIFEST_DIR] map each key to the test that proves it, one `key<TAB>test file<TAB>test name`
 * row per line (`#` comments). A key may have several rows. The test fails on a key with no row, a row for a key
 * that no longer exists, and a row whose test file or test name is gone.
 */
class ConfigKeyBehaviourInventoryTest {
    @Test
    fun `every config key has a behaviour test`() =
        runTest {
            val keys = inventory()
            File(DocsSupport.path("build").toString()).mkdirs()
            File(DocsSupport.path("build/config-keys.txt").toString()).writeText(keys.joinToString("\n"))
            val rows = manifestRows()
            val missing = keys - rows.map { it.key }.toSet()
            assertTrue(
                missing.isEmpty(),
                "${missing.size} of ${keys.size} config keys have no behaviour test:\n" + missing.joinToString("\n")
            )
        }

    @Test
    fun `every manifest row names a live key and an existing test`() =
        runTest {
            val keys = inventory()
            val problems = manifestRows().mapNotNull { row ->
                val file = File(DocsSupport.path(row.file).toString())
                when {
                    row.key !in keys -> "${row.where}: no such key '${row.key}'"
                    !file.isFile -> "${row.where}: no test file ${row.file}"
                    "fun `${row.test}`" !in file.readText() -> "${row.where}: no test `${row.test}` in ${row.file}"
                    else -> null
                }
            }
            assertEquals(emptyList(), problems)
        }

    private data class Row(
        val key: String,
        val file: String,
        val test: String,
        val where: String,
    )

    private fun manifestRows(): List<Row> =
        File(DocsSupport.path(MANIFEST_DIR).toString())
            .listFiles { f -> f.extension == "tsv" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
                    val cells = line.split('\t')
                    val where = "${file.name}:${index + 1}"
                    require(cells.size == 3) { "$where: expected key<TAB>file<TAB>test, got '$line'" }
                    Row(cells[0].trim(), cells[1].trim(), cells[2].trim(), where)
                }
            }

    /** All keys, as `section.path` for core config and `module-id:path` for a module's own config. */
    private suspend fun inventory(): Set<String> {
        val core = keysOf(Config.serializer().descriptor) +
            UnknownTopLevelKeys.known.map(::kebabToCamelCase) +
            EXTENDS_ENTRY_KEYS.map { "extends.$it" } +
            EXTENDS_CACHE_KEYS.map { "extends.cache.$it" } +
            RuleExecutionMeta.META_FIELD_NAMES.map { "rules.<rule>.$it" } +
            "reporters.<format>.$HIDE_FIELDS_KEY" +
            keysOf(AiPluginConfig.serializer().descriptor, prefix = "ai.") +
            // @Transient in AiPluginConfig (read by fromPluginConfig as durations), so not in its descriptor
            listOf("ai.streamDeadline", "ai.streamStall")
        val modules = PluginBootstrap(envReader = { null })
            .also { it.loadPluginsFromDirs(emptyList()) }
            .registry()
            .allModules()
            .flatMap { module ->
                val fromSchema = module.configSchema().map { it.name }
                val fromDefaults = (module.defaultConfig() as? JsonObject)?.keys.orEmpty()
                val fromTyped = (module as? TypedModule<*>)
                    ?.configSerializer
                    ?.descriptor
                    ?.let { keysOf(it) }
                    .orEmpty()
                (fromSchema + fromDefaults + fromTyped).map { "${module.id}:$it" }
            }
        return (core + modules).toSortedSet()
    }

    /** Element names of [descriptor], nested class elements as `parent.child`, list and map values followed in. */
    private fun keysOf(
        descriptor: SerialDescriptor,
        prefix: String = "",
        seen: Set<String> = emptySet(),
    ): List<String> {
        if (descriptor.serialName in seen) return emptyList()
        val inside = seen + descriptor.serialName
        return (0 until descriptor.elementsCount).flatMap { i ->
            val name = prefix + descriptor.getElementName(i)
            listOf(name) + nested(descriptor.getElementDescriptor(i))?.let { keysOf(it, "$name.", inside) }.orEmpty()
        }
    }

    private fun nested(child: SerialDescriptor): SerialDescriptor? {
        val unwrapped = when (child.kind) {
            StructureKind.LIST -> child.getElementDescriptor(0)
            StructureKind.MAP -> child.getElementDescriptor(1)
            else -> child
        }
        return unwrapped.takeIf { it.kind == StructureKind.CLASS && it.elementsCount > 0 }
    }

    private companion object {
        const val MANIFEST_DIR = "cli/src/jvmTest/config-keys"
    }
}
