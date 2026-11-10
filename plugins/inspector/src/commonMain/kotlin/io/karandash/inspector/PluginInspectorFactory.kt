package io.karandash.inspector

/**
 * Creates the platform-appropriate [PluginInspector] implementation.
 *
 * - JVM: [JvmPluginInspector], static JAR inspection
 * - Native: [NativePluginInspector], static byte inspection (never loads the plugin)
 */
expect fun createPluginInspector(): PluginInspector
