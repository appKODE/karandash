package io.karandash.loader.native

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginLoader
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.io.files.Path
import kotlin.time.Duration

expect class NativePluginLoader(
    registry: ModuleRegistry,
    logger: Logger = NoOpLogger,
) : PluginLoader {
    override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult

    override suspend fun unload(moduleId: String): Boolean

    override suspend fun unloadAll(shutdownTimeout: Duration)
}
