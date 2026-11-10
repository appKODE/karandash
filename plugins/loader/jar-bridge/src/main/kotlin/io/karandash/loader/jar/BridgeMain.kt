package io.karandash.loader.jar

/**
 * Thin entry point for the JAR bridge subprocess.
 *
 * This module produces a fat JAR (`karandash-jar-bridge.jar`) that the native
 * binary spawns to load and execute JVM plugin JARs via ServiceLoader.
 *
 * The fat JAR bundles:
 * - [JarBridgeServer] (stdin/stdout JSON protocol handler)
 * - Karandash SDK (Module, RuleModule, Finding, MergeRequest, etc.)
 * - kotlinx-serialization for JSON IPC
 *
 * Usage from native: `java -cp karandash-jar-bridge.jar:plugin1.jar:plugin2.jar io.karandash.loader.jar.JarBridgeServer`
 *
 * The actual main class is [JarBridgeServer.main]; this file exists only
 * to document the module's purpose.
 */
fun main(args: Array<String>) {
    JarBridgeServer.main(args)
}
