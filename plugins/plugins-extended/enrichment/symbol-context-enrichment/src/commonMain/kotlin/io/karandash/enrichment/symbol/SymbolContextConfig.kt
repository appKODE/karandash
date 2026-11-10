@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.enrichment.symbol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
internal data class SymbolChangedSubConfig(
    val enabled: Boolean = true,
)

@Serializable
internal data class SymbolContextConfig(
    @SerialName("ast-grep-path") @JsonNames("astGrepPath") val astGrepPath: String = AstGrepRunner.DEFAULT_BINARY,
    val changed: SymbolChangedSubConfig = SymbolChangedSubConfig(),
)
