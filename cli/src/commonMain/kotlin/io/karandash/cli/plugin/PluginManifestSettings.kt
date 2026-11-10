package io.karandash.cli.plugin

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.duration.KarandashDuration
import io.karandash.http.HostCredentials
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.loader.LoadResult
import io.karandash.manifest.ManifestData
import io.karandash.manifest.ManifestReader
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.registry.REGISTRY_URL
import io.karandash.registry.RegistryAuth
import io.karandash.registry.RegistryClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Project manifest the plugin commands read registry settings from and `install --pin` writes to. */
internal val PLUGIN_MANIFEST_PATH: Path = Path(".karandash/manifest.yaml")

private const val INSTALL_KEY = "install"

private val REGISTRY_TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(10)

/**
 * Reads the `plugins:` settings of [path]: the one manifest reader (plugin commands, plugin loading and the config
 * loader's missing-module check). A missing manifest yields defaults, an unreadable one warns and yields defaults.
 */
internal suspend fun readPluginManifest(
    path: Path = PLUGIN_MANIFEST_PATH,
    warn: (String) -> Unit,
): ManifestData =
    try {
        ManifestReader.parseManifest(parseManifest(readManifestText(path)))
    } catch (e: IllegalArgumentException) {
        warn("Ignoring unreadable manifest $path: ${e.message}")
        ManifestData()
    } catch (e: IOException) {
        warn("Ignoring unreadable manifest $path: ${e.message}")
        ManifestData()
    }

/** The `plugins:` block of a config, read the same way as a manifest's when no manifest file exists. */
internal fun manifestFromConfig(config: JsonElement): ManifestData = ManifestReader.parseManifest(config)

/** The manifest's `plugins.directory`, scanned in addition to the default plugin directory when plugins load; `null` when unset. */
internal suspend fun manifestPluginDir(
    warn: (String) -> Unit,
    path: Path = PLUGIN_MANIFEST_PATH,
): Path? = readPluginManifest(path, warn).pluginDir?.let(::Path)

/**
 * Loads plugins from the default directory, [extraDirs] and the manifest's `plugins.directory` -
 * the one plugin-loading entry every CLI path (root command, doctor, configured rule runs) uses.
 */
internal suspend fun PluginBootstrap.loadPluginsWithManifest(
    extraDirs: List<Path> = emptyList(),
    manifestPath: Path = PLUGIN_MANIFEST_PATH,
): LoadResult = loadPlugins(additionalDirs = extraDirs + listOfNotNull(manifestPluginDir(logger::warn, manifestPath)))

/**
 * A registry client for the manifest's `registry-url` / `token` / `auth-header`, falling back to the public registry;
 * `allow-localhost` lets it reach a loopback/private registry host.
 */
internal fun ManifestData.registryClient(
    cacheDir: Path = RegistryClient.defaultCacheDir(),
    engine: HttpClientEngine? = null,
): RegistryClient =
    (registryUrl ?: REGISTRY_URL).let { url ->
        val auth = registryToken?.let { RegistryAuth(it, registryAuthHeader ?: RegistryAuth.DEFAULT_HEADER_NAME) }
        RegistryClient(
            cacheDir = cacheDir,
            registryUrl = url,
            auth = auth,
            httpClient =
                ProviderHttpClientFactory.create(
                    engine = engine,
                    timeout = REGISTRY_TIMEOUT,
                    allowLocalhost = allowLocalhost,
                    credentials = HostCredentials.of(url, auth?.toHttpAuth()),
                ),
        )
    }

/**
 * Upserts `{id, version}` into the `plugins.install` list of [path], creating the manifest when missing.
 * The edit is made in place on the YAML text, so comments and formatting survive; only when that edit does not
 * parse to the expected manifest (flow style, unusual layout) is the file rewritten from its parsed content.
 *
 * @return an error message, or `null` on success. An unreadable manifest is left untouched.
 */
internal suspend fun pinPluginInManifest(
    id: String,
    version: String,
    path: Path = PLUGIN_MANIFEST_PATH,
): String? =
    try {
        val text = readManifestText(path)
        val pinned = pinPluginVersion(parseManifest(text), id, version)
        val edited = pinInPlace(text.lines(), id, version).joinToString("\n", postfix = "\n")
        path.safeParent?.let { createDirectories(it) }
        path.writeText(if (parseManifest(edited) == pinned) edited else toYaml(pinned))
        null
    } catch (e: IllegalArgumentException) {
        "Could not pin $id in $path: ${e.message}"
    } catch (e: IOException) {
        "Could not pin $id in $path: ${e.message}"
    }

/**
 * Sets `plugins.registry-url` in [path] to [url], creating the manifest when missing. Edited in place like
 * [pinPluginInManifest], falling back to a full rewrite when the in-place edit does not parse as expected.
 *
 * @return an error message, or `null` on success.
 */
internal suspend fun setRegistryUrlInManifest(
    url: String,
    path: Path = PLUGIN_MANIFEST_PATH,
): String? =
    try {
        val text = readManifestText(path)
        val manifest = parseManifest(text)
        val block = when (val plugins = manifest[PLUGINS_KEY]) {
            is JsonObject -> plugins
            is JsonArray -> JsonObject(mapOf(INSTALL_KEY to plugins))
            else -> JsonObject(emptyMap())
        }
        val pluginsBlock = JsonObject(block.filterKeys { it !in REGISTRY_URL_KEYS } + (REGISTRY_URL_KEY to JsonPrimitive(url)))
        val updated = JsonObject(manifest + (PLUGINS_KEY to pluginsBlock))
        val edited = registryUrlInPlace(text.lines(), url).joinToString("\n", postfix = "\n")
        path.safeParent?.let { createDirectories(it) }
        path.writeText(if (manifest[PLUGINS_KEY] !is JsonArray && parseManifest(edited) == updated) edited else toYaml(updated))
        null
    } catch (e: IllegalArgumentException) {
        "Could not set the registry URL in $path: ${e.message}"
    } catch (e: IOException) {
        "Could not set the registry URL in $path: ${e.message}"
    }

private const val PLUGINS_KEY = "plugins"
private const val PLUGINS_HEADER = "$PLUGINS_KEY:"
private const val REGISTRY_URL_KEY = "registry-url"
private val REGISTRY_URL_KEYS = setOf(REGISTRY_URL_KEY, "registryUrl", "registry_url")

/** Line-based upsert of `registry-url` under a block-style `plugins:` mapping; the caller verifies the result. */
private fun registryUrlInPlace(
    lines: List<String>,
    url: String,
): List<String> {
    val out = lines.dropLastWhile { it.isBlank() }.toMutableList()
    val value = JsonPrimitive(url).toString()
    val pluginsAt = out.indexOfFirst { PLUGINS_LINE.matches(it) }
    if (pluginsAt < 0) return out + listOf(PLUGINS_HEADER, "  $REGISTRY_URL_KEY: $value")
    val children = (pluginsAt + 1 until blockEnd(out, pluginsAt, 0)).filter { isContent(out[it]) }
    val indent = children.minOfOrNull { indentOf(out[it]) } ?: 2
    val existing = children.firstOrNull { indentOf(out[it]) == indent && out[it].trim().substringBefore(':') in REGISTRY_URL_KEYS }
    val line = "${" ".repeat(indent)}$REGISTRY_URL_KEY: $value"
    if (existing == null) out.add(pluginsAt + 1, line) else out[existing] = line
    return out
}

/** Returns [manifest] with `{id, version}` replacing any declaration of [id] in `plugins.install`, or appended. */
internal fun pinPluginVersion(
    manifest: JsonObject,
    id: String,
    version: String,
): JsonObject {
    val block = when (val plugins = manifest[PLUGINS_KEY]) {
        is JsonObject -> plugins
        is JsonArray -> JsonObject(mapOf(INSTALL_KEY to plugins))
        else -> JsonObject(emptyMap())
    }
    val install = block[INSTALL_KEY] as? JsonArray ?: JsonArray(emptyList())
    val existing = install.firstOrNull { declarationId(it) == id } as? JsonObject ?: JsonObject(emptyMap())
    val pinned = JsonObject(existing + mapOf("id" to JsonPrimitive(id), "version" to JsonPrimitive(version)))
    val updated = if (install.any { declarationId(it) == id }) {
        install.map { if (declarationId(it) == id) pinned else it }
    } else {
        install + pinned
    }
    return JsonObject(manifest + (PLUGINS_KEY to JsonObject(block + (INSTALL_KEY to JsonArray(updated)))))
}

private fun readManifestText(path: Path): String = if (SystemFileSystem.exists(path)) path.readText() else ""

private suspend fun parseManifest(text: String): JsonObject {
    val element = YamlFormatModule().parseToJsonElement(text)
    return requireNotNull(element as? JsonObject) { "manifest is not a YAML mapping" }
}

/**
 * Line-based pin of [id] to [version] under a block-style `plugins:` / `install:` list, keeping every other line
 * (comments included) as written. Unusual layouts may come out wrong; the caller verifies the result by parsing it.
 */
internal fun pinInPlace(
    lines: List<String>,
    id: String,
    version: String,
): List<String> {
    val out = lines.dropLastWhile { it.isBlank() }.toMutableList()
    val pluginsAt = out.indexOfFirst { PLUGINS_LINE.matches(it) }
    // Flow style (`plugins: [..]`, `install: [..]`) is left to the full rewrite: an empty result never verifies.
    if (pluginsAt < 0 && out.any { it.startsWith(PLUGINS_HEADER) }) return emptyList()
    if (pluginsAt < 0) return out + listOf(PLUGINS_HEADER, "  $INSTALL_KEY:", "    - id: $id", "      version: \"$version\"")
    val installAt = (pluginsAt + 1 until blockEnd(out, pluginsAt, 0)).firstOrNull { out[it].trim().startsWith("$INSTALL_KEY:") }
    if (installAt == null) {
        out.addAll(pluginsAt + 1, listOf("  $INSTALL_KEY:", "    - id: $id", "      version: \"$version\""))
        return out
    }
    if (out[installAt].substringAfter(':').substringBefore('#').isNotBlank()) return emptyList()
    val installIndent = indentOf(out[installAt])
    val items = (installAt + 1 until blockEnd(out, installAt, installIndent, sameIndentItems = true))
    val itemAt = items.firstOrNull { itemId(out[it]) == id }
    val pad = " ".repeat(
        items.firstOrNull { out[it].trimStart().startsWith("- ") }?.let { indentOf(out[it]) } ?: (installIndent + 2),
    )
    val versionLine = "$pad  version: \"$version\""
    when {
        itemAt == null -> {
            out.addAll(items.last + 1, listOf("$pad- id: $id", versionLine))
        }

        out[itemAt].trimStart().startsWith("- id:") -> {
            val versionAt = (itemAt + 1 until blockEnd(out, itemAt, indentOf(out[itemAt]))).firstOrNull {
                out[it].trim().startsWith("version:")
            }
            if (versionAt == null) out.add(itemAt + 1, versionLine) else out[versionAt] = versionLine
        }

        else -> {
            out[itemAt] = "$pad- id: $id"
            out.add(itemAt + 1, versionLine)
        }
    }
    return out
}

private val PLUGINS_LINE = Regex("^plugins:\\s*(#.*)?$")

private fun indentOf(line: String): Int = line.length - line.trimStart().length

private fun isContent(line: String): Boolean = line.isNotBlank() && !line.trimStart().startsWith("#")

/**
 * Index just past the last content line of the block opened at [start]: the block runs until a content line indented at
 * most [indent] (a `- ` item at exactly [indent] still belongs to it when [sameIndentItems]). Trailing comments stay outside.
 */
private fun blockEnd(
    lines: List<String>,
    start: Int,
    indent: Int,
    sameIndentItems: Boolean = false,
): Int {
    val end = (start + 1 until lines.size).firstOrNull { i ->
        val sameIndentItem = sameIndentItems && indentOf(lines[i]) == indent && lines[i].trimStart().startsWith("- ")
        isContent(lines[i]) && indentOf(lines[i]) <= indent && !sameIndentItem
    } ?: lines.size
    return (end - 1 downTo start + 1).firstOrNull { isContent(lines[it]) }?.plus(1) ?: (start + 1)
}

/** The plugin id declared by a `- id: x` or `- x` list item line, else `null`. */
private fun itemId(line: String): String? {
    val item = line
        .trimStart()
        .takeIf { it.startsWith("- ") }
        ?.removePrefix("- ")
        ?.substringBefore(" #")
        ?.trim()
    return item
        ?.removePrefix("id:")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() && ':' !in it }
}

private fun declarationId(declaration: JsonElement): String? =
    when (declaration) {
        is JsonPrimitive -> declaration.contentOrNull
        is JsonObject -> (declaration["id"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

/** Block-style YAML for [obj]; scalars are written as JSON literals, which YAML reads unchanged. */
internal fun toYaml(
    obj: JsonObject,
    indent: String = "",
): String =
    buildString {
        for ((rawKey, value) in obj) {
            val key = if (rawKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) rawKey else JsonPrimitive(rawKey).toString()
            when {
                value is JsonObject && value.isNotEmpty() -> {
                    append("$indent$key:\n").append(toYaml(value, "$indent  "))
                }

                value is JsonArray && value.isNotEmpty() -> {
                    append("$indent$key:\n")
                    value.forEach { item ->
                        val itemIndent = "$indent    "
                        val body = if (item is JsonObject && item.isNotEmpty()) toYaml(item, itemIndent) else "$itemIndent$item\n"
                        append("$indent  - ").append(body.removePrefix(itemIndent))
                    }
                }

                else -> {
                    append("$indent$key: $value\n")
                }
            }
        }
    }
