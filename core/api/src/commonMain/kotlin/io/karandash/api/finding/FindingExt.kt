package io.karandash.api.finding

import io.karandash.models.finding.Finding

/**
 * Returns a [RuleIdentifier] for this finding's [Finding.rule].
 *
 * Allows typed dispatch on findings without changing [Finding]'s serialized shape.
 */
public val Finding.ruleIdentifier: RuleIdentifier get() = RuleIdentifier.from(rule)
