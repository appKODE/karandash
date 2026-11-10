package io.karandash.enrichment.symbol

import kotlinx.serialization.Serializable

@Serializable
enum class SymbolKind {
    CLASS,
    FUNCTION,
    METHOD,
    STRUCT,
}

@Serializable
data class SymbolInfo(
    val kind: SymbolKind,
    val name: String,
    val signature: String,
    val startLine: Int,
)

@Serializable
internal data class AstGrepMatchRange(
    val start: AstGrepPosition,
)

@Serializable
internal data class AstGrepPosition(
    val line: Int,
    val column: Int,
)

@Serializable
internal data class AstGrepMetaVar(
    val text: String,
)

@Serializable
internal data class AstGrepMatch(
    val text: String,
    val range: AstGrepMatchRange,
    val metaVariables: Map<String, AstGrepMetaVar> = emptyMap(),
)
