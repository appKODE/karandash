package io.karandash.utils.finding

import io.karandash.models.finding.Location

/**
 * Generates semantic fingerprints for [io.karandash.models.finding.Finding] deduplication.
 *
 * Two findings with the same fingerprint and location are considered duplicates.
 * The fingerprint normalizes the message (strips numbers, extra whitespace)
 * to catch semantically equivalent findings with different specific values.
 *
 * Uses a deterministic hash function suitable for deduplication (not cryptographic).
 */
object FindingFingerprint {
    private val DIGITS_REGEX = Regex("\\d+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Generates a semantic fingerprint for deduplication.
     *
     * @param rule The rule identifier.
     * @param location The finding location, or null.
     * @param message The human-readable finding message.
     * @return A 16-character hex fingerprint string.
     */
    fun generate(
        rule: String,
        location: Location?,
        message: String,
    ): String {
        val normalized = message
            .replace(DIGITS_REGEX, "N")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .lowercase()
        val locationKey = locationToKey(location)
        val raw = "$rule|$locationKey|$normalized"
        return stableHash(raw)
    }

    private fun locationToKey(location: Location?): String =
        when (location) {
            is Location.FileRef -> "file:${location.path}:${location.line ?: 0}"
            is Location.CommitRef -> "commit:${location.sha}"
            null -> "none"
        }

    /**
     * Produces a stable 16-character hex hash.
     *
     * Uses a FNV-1a-inspired 64-bit hash for good distribution
     * without requiring a cryptographic dependency.
     */
    internal fun stableHash(input: String): String {
        val fnvOffset = -3750763034362895579L // 0xcbf29ce484222325
        val fnvPrime = 1099511628211L // 0x00000100000001B3
        var hash = fnvOffset
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= fnvPrime
        }
        return hash.toULong().toString(HEX_RADIX).padStart(HEX_LENGTH, '0')
    }

    private const val HEX_RADIX = 16
    private const val HEX_LENGTH = 16
}
