package io.karandash.cli.plugin

import io.karandash.registry.InstalledPluginInfo
import io.karandash.registry.InstalledPluginLister
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

/**
 * Lister with one installed plugin `acme` at 1.0.0 that supports [formats]; every download returns [download] and
 * is recorded in [downloads], its signature keys in [signatureKeys]; local installs return [localInstall].
 */
class FakePluginLister(
    private val download: Result<Pair<String?, String>> = Result.failure(IllegalStateException("download refused")),
    private val formats: Set<String> = setOf("jar"),
    private val localInstall: Result<InstalledPluginInfo> = Result.failure(IllegalStateException("unused")),
) : InstalledPluginLister {
    val downloads = mutableListOf<String>()
    val signatureKeys = mutableListOf<List<ByteArray>?>()

    override fun defaultPluginDirPath() = "/p"

    override fun isDirectory(path: String) = true

    override suspend fun listInstalledPlugins(pluginDirPath: String) = listOf(InstalledPluginInfo("acme", "1.0.0", "Acme", "/p/acme.jar"))

    override suspend fun downloadAndUpdate(
        downloadUrl: String,
        pluginDirPath: String,
        oldSourcePath: String?,
        pluginId: String,
        expectedChecksum: String?,
        allowLocalhost: Boolean,
        signatureKeys: List<ByteArray>?,
    ): Result<Pair<String?, String>> =
        download.also {
            downloads += downloadUrl
            this.signatureKeys += signatureKeys
        }

    override suspend fun shutdown() = Unit

    override fun supportedFormats() = formats

    override suspend fun verifyPlugin(pluginPath: String): String? = null

    override suspend fun installPlugin(
        sourcePath: String,
        pluginDir: String,
    ): Result<InstalledPluginInfo> = localInstall

    override suspend fun removePlugin(
        pluginId: String,
        pluginDir: String,
    ) = false
}

/** A registry index with one `acme` 2.0.0 entry downloadable from [url]; [extra] adds raw JSON fields to the entry. */
fun registryEngine(
    url: String,
    minHostVersion: String = "0.1.0",
    extra: String = "",
): MockEngine =
    MockEngine {
        respond(
            """
            {"version":"1","lastUpdated":"now","plugins":[{
              "id":"acme","name":"Acme","description":"d","author":"a","version":"2.0.0","type":"rule","tags":[],
              "url":"$url","checksum":"sha256:00","minHostVersion":"$minHostVersion","repository":"r","documentation":"d"$extra
            }]}
            """.trimIndent(),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
