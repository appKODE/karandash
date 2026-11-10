package io.karandash.loader.jar

import java.net.URL
import java.net.URLClassLoader

/**
 * Isolated classloader for a single plugin JAR.
 *
 * Each plugin gets its own classloader so that plugins cannot interfere
 * with each other's dependencies. The parent is set to the application
 * classloader so that the plugin-sdk types are shared.
 */
internal class PluginClassLoader(
    jarUrl: URL,
    parent: ClassLoader = PluginClassLoader::class.java.classLoader,
) : URLClassLoader(arrayOf(jarUrl), parent),
    AutoCloseable {
    /**
     * Human-readable label for logging / debugging.
     */
    val label: String = jarUrl.path.substringAfterLast('/')

    override fun toString(): String = "PluginClassLoader($label)"
}
