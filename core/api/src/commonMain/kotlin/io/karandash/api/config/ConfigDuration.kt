package io.karandash.api.config

import io.karandash.api.exception.ConfigParseException
import io.karandash.duration.KarandashDuration

/**
 * Parses the duration string a config key holds, reporting bad input as a user-facing [ConfigParseException].
 *
 * config readers go through this instead of [KarandashDuration.parse], whose `error()` would surface as an
 * internal crash. `core/common` cannot throw [ConfigParseException] itself (it sits below `core/api`).
 *
 * @param key the config key, named in the message
 * @param value the raw string, e.g. `30s`, `1h 30m`, `never`
 * @throws ConfigParseException when [value] is not a duration
 */
public fun parseConfigDuration(
    key: String,
    value: String
): KarandashDuration =
    try {
        KarandashDuration.parse(value)
    } catch (e: IllegalStateException) {
        throw ConfigParseException(
            "'$key' is not a duration: ${e.message}",
            hint = "Write '$key' with a unit, e.g. '30s', '5m', '2h' or '1h 30m'.",
        )
    }
