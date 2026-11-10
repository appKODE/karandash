@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import platform.posix.strdup

private fun String.toCProfileString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Return the unique identifier for this profile.
 *
 * @return Null-terminated UTF-8 string with the profile ID.
 */
@CName("karandash_profile_id")
fun profileId(): CPointer<ByteVar> = "kotlin-example-profile".toCProfileString()

/**
 * Return a short human-readable summary of this profile.
 *
 * @return Null-terminated UTF-8 string with the profile summary.
 */
@CName("karandash_profile_summary")
fun profileSummary(): CPointer<ByteVar> = "Example Kotlin/Native profile".toCProfileString()

/**
 * Return a JSON array of tags describing this profile.
 *
 * @return Null-terminated UTF-8 JSON array of tag strings.
 */
@CName("karandash_profile_tags")
fun profileTags(): CPointer<ByteVar> = """["kotlin","example"]""".toCProfileString()

/**
 * Return a JSON object with rule configurations contributed by this profile.
 *
 * @return Null-terminated UTF-8 JSON object mapping rule IDs to their config objects.
 */
@CName("karandash_profile_rule_configs")
fun profileRuleConfigs(): CPointer<ByteVar> =
    """{"titleFormat":{"pattern":"^.*$"}}""".toCProfileString()

/**
 * Return a JSON object with reporter configurations contributed by this profile.
 *
 * @return Null-terminated UTF-8 JSON object mapping reporter IDs to their config objects.
 */
@CName("karandash_profile_reporter_configs")
fun profileReporterConfigs(): CPointer<ByteVar> = "{}".toCProfileString()
