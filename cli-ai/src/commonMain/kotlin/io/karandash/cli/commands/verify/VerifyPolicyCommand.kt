package io.karandash.cli.commands.verify

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.ConfiguredRulesSource
import io.karandash.cli.commands.runOrExit
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.PolicyConfig
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.architecture.PolicyDefinition
import io.karandash.plugins.ai.core.architecture.PolicyDefinitions
import io.karandash.plugins.ai.core.architecture.PolicyIssueSeverity
import io.karandash.plugins.ai.core.architecture.PolicyVerifier
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray

/**
 * CLI command that verifies the top-level `policies:` block for logical consistency.
 *
 * Each policy effect (`enabled`, `severity`, `autoApprove`, `autoFix`, `scripts`, `dangerfile`) is checked
 * separately with [PolicyVerifier]: two policies with the same `when:` condition that set the same effect to
 * different values on overlapping modules are reported as a contradiction. Exits 1 on any contradiction.
 */
class VerifyPolicyCommand(
    private val rules: ConfiguredRulesSource,
) : SuspendCliktCommand(name = "verify-policy") {
    override fun help(context: Context) = "Verify policy configuration for logical consistency"

    private val configOptions by ConfigFileOptions()
    private val configFile get() = configOptions.path

    override suspend fun suspendRun() {
        val policies = rules.runOrExit("Policy verification failed", { echo(it, err = true) }, configFile, { false }) { runner ->
            runner.config.policies.orEmpty()
        }
        if (policies.isEmpty()) {
            echo("No 'policies:' block in the config; nothing to verify.")
            return
        }
        val result = PolicyVerifier.verify(toDefinitions(policies))
        echo(PolicyVerifier.formatReport(result))
        if (result.issues.any { it.severity == PolicyIssueSeverity.ERROR }) throw ProgramResult(ExitCodes.VALIDATION_FAILED)
    }

    private fun toDefinitions(policies: List<PolicyConfig>): List<PolicyDefinition> =
        PolicyDefinitions.from(KarandashJson.Default.encodeToJsonElement(ListSerializer(PolicyConfig.serializer()), policies).jsonArray)
}
