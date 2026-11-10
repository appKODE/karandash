package io.karandash.cli.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.exception.ConfigParseException
import io.karandash.cli.commands.validate.withHint

/** [ConfigLoader.loadConfig], or null after [report]ing `Config load failed: …` (with its hint) for a config that does not load. */
internal suspend fun ConfigLoader.loadOrReport(report: (String) -> Unit): ConfigLoadStage? =
    try {
        loadConfig()
    } catch (e: ConfigException) {
        report(withHint("Config load failed: ${e.message}", e.hint))
        null
    } catch (e: ConfigParseException) {
        report(withHint("Config load failed: ${e.message}", e.hint))
        null
    }
