package io.karandash.lsp

import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Represents the context where completion is requested. */
sealed class CompletionContext {
    object TopLevel : CompletionContext()

    object RulesBlock : CompletionContext()

    object ReportersBlock : CompletionContext()

    object PlatformsBlock : CompletionContext()

    object ProfileBlock : CompletionContext()

    object PoliciesBlock : CompletionContext()

    @Serializable
    data class ValidatorBlock(
        val validatorName: String
    ) : CompletionContext()

    object PropertyValue : CompletionContext()
}

internal const val DETAIL_BLOCK = "Block"
internal const val DETAIL_STRING = "String"
internal const val DETAIL_BOOLEAN = "Boolean"

/** A snippet completion item: [insertText] may hold `$0`/`$1` tab stops. */
internal fun snippetItem(
    label: String,
    kind: CompletionItemKind,
    detail: String,
    documentation: String,
    insertText: String,
): CompletionItem =
    CompletionItem(label).also {
        it.kind = kind
        it.detail = detail
        it.documentation = Either.forLeft(documentation)
        it.insertText = insertText
        it.insertTextFormat = InsertTextFormat.Snippet
    }

/** Creates a block completion item (e.g. `rules { ... }`). */
internal fun createBlockCompletion(
    label: String,
    documentation: String,
): CompletionItem = snippetItem(label, CompletionItemKind.Keyword, DETAIL_BLOCK, documentation, "$label {\n\t$0\n}")

/** Creates a property completion item (e.g. `pattern = ...`). */
internal fun createPropertyCompletion(
    label: String,
    type: String,
    documentation: String,
): CompletionItem = snippetItem(label, CompletionItemKind.Property, type, documentation, "$label = $0")
