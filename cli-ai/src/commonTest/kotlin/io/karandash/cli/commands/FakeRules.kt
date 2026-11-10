package io.karandash.cli.commands

import io.karandash.config.Config
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding

/**
 * A [ConfiguredRulesSource] over a fixed [config]: [validate] stands in for the rules, [failure] is thrown on load.
 * Records the config path and the rule filter it was asked for.
 */
class FakeRules(
    private val config: Config = Config(),
    private val failure: Exception? = null,
    private val fetched: (String) -> MergeRequest = { error("no MR fetch expected: $it") },
    private val validate: (MergeRequest) -> List<Finding> = { emptyList() },
) : ConfiguredRulesSource {
    val paths = mutableListOf<String?>()
    val filters = mutableListOf<(String) -> Boolean>()

    override suspend fun <T> withRules(
        configPath: String?,
        ruleFilter: (String) -> Boolean,
        block: suspend (ConfiguredRules) -> T,
    ): T {
        paths += configPath
        filters += ruleFilter
        failure?.let { throw it }
        return block(
            object : ConfiguredRules {
                override val config = this@FakeRules.config

                override suspend fun validate(mr: MergeRequest) = this@FakeRules.validate(mr)

                override suspend fun fetchMergeRequest(url: String) = fetched(url)
            },
        )
    }
}
