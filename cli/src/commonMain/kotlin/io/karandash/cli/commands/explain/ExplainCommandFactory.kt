package io.karandash.cli.commands.explain

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ConfigLoader

/** `explain`, reading its config through [ConfigLoader] like every other command. */
internal fun explainCommand(bootstrap: PluginBootstrap): ExplainCommand =
    ExplainCommand(bootstrap) { configPath ->
        ConfigLoader.forCommand(configPath, bootstrap).loadConfig().let { ExplainConfigSource(it.configFilePath, it.config) }
    }
