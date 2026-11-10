package io.karandash.api.finding

import kotlinx.serialization.Serializable

@Serializable
public enum class MessageCode {
    MISSING_AUTH,
    INCOMPLETE_AUTH,
    MISSING_DEPENDENCY,
    CONFIG_INVALID,
    CUSTOM
}
