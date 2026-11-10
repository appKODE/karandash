package io.karandash.cli.commands.cache

import io.karandash.bootstrap.PluginBootstrap
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.runBlocking

/** `cache push` loads the config, so it needs a bootstrap with the built-in modules loaded, as the CLI gives it. */
internal fun cachePushCommand(
    engine: HttpClientEngine? = null,
    awsEnv: Map<String, String> = emptyMap(),
): CachePushCommand =
    CachePushCommand(
        PluginBootstrap(envReader = { null }).also { runBlocking { it.loadPluginsFromDirs(emptyList()) } },
        engine,
        awsEnv = { awsEnv[it] },
    )
