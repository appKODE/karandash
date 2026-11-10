package io.karandash.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** The `karandash` ruleset: the docs/development/conventions.md error-handling, coroutine and config-layer conventions. */
class KarandashRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId(RULE_SET_ID)

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                ::GenericCatchWithoutBoundary,
                ::SuspendCatchWithoutCancellation,
                ::ForbiddenRunCatching,
                ::ForbiddenWithTimeout,
                ::UnsanctionedRunBlocking,
                ::UnownedCoroutineScope,
                ::RawConsoleOutput,
                ::InlineJsonBuilder,
                ::DirectHttpClient,
                ::DirectGetenv,
                ::UntypedConfigMap,
                ::SuppressOutsidePolicy,
                ::DynamicRegex,
                ::RawTestTempDirectory,
            ),
        )

    companion object {
        const val RULE_SET_ID = "karandash"
    }
}
