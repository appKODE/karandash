package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding

/**
 * Validates an arbitrary [MergeRequest] with the run's configured rules, minus the rules an extension computes
 * (AI analyzers), with no rule cache and no platform calls. Provided as [RunServiceKeys.RULE_VALIDATOR].
 */
@ExperimentalKarandashApi
public fun interface MergeRequestValidator {
    public suspend fun validate(mr: MergeRequest): List<Finding>
}
