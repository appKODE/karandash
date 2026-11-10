package io.karandash.plugins.ai.providers

/**
 * How many `ai.consensusModels` must report a finding for consensus to keep it.
 *
 * - [INTERSECTION]: every model (highest confidence, fewest results).
 * - [MAJORITY]: more than half the models.
 * - [FIRST_AGREEMENT]: any two models.
 */
enum class ConsensusStrategy {
    INTERSECTION,
    MAJORITY,
    FIRST_AGREEMENT;

    /** The number of the [models] consensus models that must agree on a finding. */
    fun minAgreement(models: Int): Int =
        when (this) {
            INTERSECTION -> models
            MAJORITY -> models / 2 + 1
            FIRST_AGREEMENT -> minOf(2, models)
        }

    companion object {
        /**
         * Parses a strategy name case-insensitively, defaulting to [INTERSECTION] on unknown values.
         *
         * @param value The raw string from config (e.g. "intersection", "majority").
         * @return The matching [ConsensusStrategy], or [INTERSECTION] if unrecognised.
         */
        fun fromString(value: String): ConsensusStrategy =
            entries.firstOrNull { it.name.equals(value.replace('-', '_'), ignoreCase = true) } ?: INTERSECTION
    }
}
