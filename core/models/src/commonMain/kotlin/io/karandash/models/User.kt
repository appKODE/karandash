package io.karandash.models

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val username: String,
    val name: String,
    val email: String? = null,
)
