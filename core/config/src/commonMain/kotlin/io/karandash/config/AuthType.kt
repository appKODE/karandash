package io.karandash.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication type for provider and integration configurations.
 *
 * Used by modules that need to authenticate with external services (e.g., Jira).
 *
 * Serialized using lowercase `@SerialName` values so user configs can use `type: bearer`
 * rather than `type: BEARER`.
 */
@Serializable
enum class AuthType(
    val value: String
) {
    @SerialName("token")
    TOKEN("token"),

    @SerialName("basic")
    BASIC("basic"),

    @SerialName("header")
    HEADER("header"),

    @SerialName("bearer")
    BEARER("bearer"),
    ;

    companion object {
        fun fromString(value: String): AuthType =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown AuthType: '$value'")
    }
}
