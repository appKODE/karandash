package io.karandash.api.finding

import kotlinx.serialization.Serializable

@Serializable
public data class ModuleMessage(
    val code: MessageCode,
    val text: String
)
