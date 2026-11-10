package io.karandash.loader

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Path as JavaPath

/**
 * Test helper to convert kotlinx.io.files.Path to java.io.File.
 */
fun Path.toFile(): File = File(this.toString())

/**
 * Test helper to convert java.nio.file.Path to kotlinx.io.files.Path.
 */
fun JavaPath.toKotlinPath(): Path = Path(this.toString())

/**
 * Test helper to convert list of java.nio.file.Path to kotlinx.io.files.Path.
 */
fun List<JavaPath>.toKotlinPaths(): List<Path> = this.map { it.toKotlinPath() }
