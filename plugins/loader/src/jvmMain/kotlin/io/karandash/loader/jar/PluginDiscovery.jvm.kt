package io.karandash.loader.jar

import io.karandash.loader.PluginDiscovery
import java.io.File

// JVM-specific plugin discovery extensions for JAR files.

/** [PluginDiscovery.defaultPluginDirPath] as java.io.File (JVM convenience): same `$HOME`-first source as install. */
val PluginDiscovery.defaultPluginDirAsFile: File
    get() = File(defaultPluginDirPath())

/**
 * Discover all JAR files in the given directories.
 *
 * @param directories Directories to scan (non-recursive). Directories
 *   that do not exist are silently skipped.
 * @return List of JAR [File]s sorted by name for deterministic load order.
 */
fun PluginDiscovery.discoverJars(directories: List<File> = listOf(defaultPluginDirAsFile)): List<File> =
    directories
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir
                .listFiles { file -> file.extension == "jar" && file.isFile }
                ?.toList()
                ?: emptyList()
        }.sortedBy { it.name }

/**
 * Discover JARs in a single directory.
 */
fun PluginDiscovery.discoverJars(directory: File): List<File> = discoverJars(listOf(directory))
