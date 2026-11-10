package io.karandash.loader.jar

import io.karandash.subprocess.JarBridgeClient
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import kotlinx.serialization.Serializable

/**
 * Metadata extracted from a JAR plugin file via the JarBridge subprocess.
 */
@Serializable
data class JarPluginMetadata(
    val id: String,
    val name: String,
    val version: String,
)

/**
 * Reads plugin metadata from a `.jar` file by spawning a JVM subprocess
 * with the JAR bridge. Requires `java 17+` on PATH or via `JAVA_HOME`.
 *
 * Gracefully returns `null` or empty list when Java is not available.
 */
object JarMetadataReader {
    /**
     * Discover all modules in a JAR plugin file.
     *
     * @return list of module metadata, or empty list if Java/bridge unavailable
     */
    suspend fun readMetadata(jarPath: String): List<JarPluginMetadata> {
        val java = JarBridgeClient.findJava() ?: return emptyList()
        val bridgeJar = JarBridgeClient.findBridgeJar() ?: return emptyList()
        val classpath = "$bridgeJar:$jarPath"

        val client = JarBridgeClient(java, classpath)
        val response = client.send(
            JarBridgeRequest(method = JarBridgeProtocol.METHOD_DISCOVER)
        )

        val modules = response.modules
        if (!response.success || modules == null) return emptyList()

        return modules.map {
            JarPluginMetadata(id = it.id, name = it.name, version = it.version)
        }
    }
}
