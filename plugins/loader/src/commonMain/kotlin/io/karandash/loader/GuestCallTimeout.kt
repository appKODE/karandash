package io.karandash.loader

import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Limit on a single call into guest code (JS, WASM, native; the JAR subprocess bridge adds JVM start-up), so a
 * plugin stuck in a loop cannot hang the CLI.
 *
 * Plugins load before the config file is parsed, so the knob is the [ENV_VAR] environment variable
 * (`30s`, `2m`, ISO-8601 `PT45S`, or a bare number of seconds), not a config key.
 */
object GuestCallTimeout {
    const val ENV_VAR = "KARANDASH_PLUGIN_CALL_TIMEOUT"
    val DEFAULT: Duration = 30.seconds

    fun fromEnvironment(
        logger: Logger,
        env: (String) -> String? = PlatformEnvironment::getEnv,
    ): Duration {
        val raw = env(ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
        val parsed = Duration.parseOrNull(raw) ?: raw.toLongOrNull()?.seconds
        if (parsed == null || !parsed.isPositive()) {
            logger.warn("Ignoring $ENV_VAR='$raw' (expected a positive duration such as 30s or 2m); using $DEFAULT")
            return DEFAULT
        }
        logger.info("Plugin guest-call timeout is $parsed ($ENV_VAR)")
        return parsed
    }
}
