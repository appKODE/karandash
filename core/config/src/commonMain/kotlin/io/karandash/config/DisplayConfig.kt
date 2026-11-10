package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * Git display preferences.
 *
 * @property shortShaLength Number of characters used for abbreviated SHA hashes
 *   in finding text. Coerced to `[4, 40]`. The host applies it per run via `ShortSha.configure`; the SHA is part of
 *   the finding message, so changing it changes message-based dedup/baseline keys.
 */
@Serializable
data class DisplayGitConfig(
    val shortShaLength: Int = DEFAULT_SHORT_SHA_LENGTH,
) {
    /** Effective length; an out-of-range value is clamped (and warned) instead of failing the config. */
    val effectiveShortShaLength: Int get() = shortShaLength.coerceIn(MIN_SHA_LENGTH, MAX_SHA_LENGTH)

    companion object {
        const val DEFAULT_SHORT_SHA_LENGTH = 7
        private const val MIN_SHA_LENGTH = 4
        private const val MAX_SHA_LENGTH = 40
    }
}

/**
 * Config watcher display preferences.
 *
 * @property debounce Debounce interval for config file change detection.
 */
@Serializable
data class DisplayWatcherConfig(
    val debounce: KarandashDuration = KarandashDuration.ofMillis(DEFAULT_DEBOUNCE_MS),
) {
    /** Coerced to `[0, 60s]`. */
    val effectiveDebounce: KarandashDuration get() = debounce.coerceIn(KarandashDuration.ZERO, MAX_DEBOUNCE)

    companion object {
        private val MAX_DEBOUNCE = KarandashDuration.ofSeconds(60)
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}

/**
 * Console output display preferences.
 *
 * @property successMark Character or string used to indicate success in console output.
 * @property failureMark Character or string used to indicate failure in console output.
 */
@Serializable
data class DisplayConsoleConfig(
    val successMark: String = "\u2713",
    val failureMark: String = "\u2717",
)

/**
 * Unified display preferences for user-facing output.
 *
 * ```yaml
 * display:
 *   git:
 *     short-sha-length: 8
 *   watcher:
 *     debounce: "1s"
 *   console:
 *     success-mark: "OK"
 *     failure-mark: "FAIL"
 * ```
 */
@Serializable
data class DisplayConfig(
    val git: DisplayGitConfig = DisplayGitConfig(),
    val watcher: DisplayWatcherConfig = DisplayWatcherConfig(),
    val console: DisplayConsoleConfig = DisplayConsoleConfig(),
) {
    /** One warning per field whose configured value is outside its bounds and was clamped. */
    fun boundsWarnings(): List<String> =
        listOfNotNull(
            clampWarning("display.git.shortShaLength", git.shortShaLength, git.effectiveShortShaLength),
            clampWarning("display.watcher.debounce", watcher.debounce, watcher.effectiveDebounce),
        )
}
