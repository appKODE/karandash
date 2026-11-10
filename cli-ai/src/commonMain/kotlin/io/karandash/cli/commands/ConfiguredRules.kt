package io.karandash.cli.commands

import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.api.exception.KarandashException
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding

/** The configured rules loaded outside the full `validate` pipeline (implemented by `ConfiguredRuleRunner` in cli). */
interface ConfiguredRules {
    val config: Config

    /** Runs every rule kept in [config] on [mr] and returns their findings. */
    suspend fun validate(mr: MergeRequest): List<Finding>

    /** Fetches the MR/PR at [url] with the platform service configured under `platforms:`. */
    suspend fun fetchMergeRequest(url: String): MergeRequest
}

/** Loads [ConfiguredRules] for `mutate`, `verify-policy` and `test-rule`; cli supplies the implementation. */
interface ConfiguredRulesSource {
    /**
     * Loads the config at [configPath] (auto-discovered when null), keeps only the rules accepted by
     * [ruleFilter], and runs [block] with them.
     */
    suspend fun <T> withRules(
        configPath: String?,
        ruleFilter: (String) -> Boolean = { true },
        block: suspend (ConfiguredRules) -> T,
    ): T
}

/** [ConfiguredRulesSource.withRules], with a config error passed to [report] as "[failure]: <message>" and exit code CONFIG_ERROR. */
suspend fun <T> ConfiguredRulesSource.runOrExit(
    failure: String,
    report: (String) -> Unit,
    configPath: String?,
    ruleFilter: (String) -> Boolean = { true },
    block: suspend (ConfiguredRules) -> T,
): T =
    try {
        withRules(configPath, ruleFilter, block)
    } catch (e: KarandashException) {
        report("$failure: ${e.message}")
        throw ProgramResult(ExitCodes.CONFIG_ERROR)
    }
