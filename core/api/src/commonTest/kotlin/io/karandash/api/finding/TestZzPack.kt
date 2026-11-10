package io.karandash.api.finding

import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.TranslationPack
import io.karandash.models.finding.Translations

/** A made-up `zz` pack with marker texts, so a test sees which strings went through the pack. */
internal object TestZzPack {
    const val LANGUAGE = "zz"

    fun register() =
        Translations.register(
            TranslationPack(
                LANGUAGE,
                PluralRule.EAST_SLAVIC,
                mapOf(
                    "gated: %1" to "GATED[%1]",
                    "trivial diff: %1" to "TRIVIAL[%1]",
                    "lockfile" to "LOCK",
                    "generated" to "GEN",
                    "timed out" to "TIMEOUT",
                    "provider error: %1" to "PROVIDER[%1]",
                    "%1 files exceeds maxFiles=%2" to "FILES[%1>%2]",
                    "dry run" to "DRY",
                    "AI: not run — %1" to "AI-OFF[%1]",
                    "reviewed %1 files" to "REVIEWED[%1]",
                    "%d chunk skipped|%d chunks skipped|%d chunks skipped" to "CHUNK1[%d]|CHUNKS2[%d]|CHUNKS5[%d]",
                    "%1 truncated" to "TRUNC[%1]",
                    "left out: %1" to "OUT[%1]",
                    "%1 and %2 more" to "%1 +%2",
                    "%1 (%2 of %d hunks)|%1 (%2 of %d hunks)|%1 (%2 of %d hunks)" to "%1 H1[%2/%d]|%1 H2[%2/%d]|%1 H5[%2/%d]",
                ),
            ),
        )
}
