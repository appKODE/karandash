package io.karandash.inspector

actual fun createPluginInspector(): PluginInspector = JvmPluginInspector()
