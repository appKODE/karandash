package io.karandash.lsp

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Property completions available inside a `policy { }` entry in the `policies {}` block.
 */
internal val POLICY_PROPERTY_COMPLETIONS: List<CompletionItem> = listOf(
    snippetItem(
        "when",
        CompletionItemKind.Property,
        DETAIL_STRING,
        "Condition expression that must evaluate to true for this policy to apply. " +
            "Supports: `is_draft`, `branch_matches(pattern)`, `has_ticket(KEY)`, " +
            "`change_count > N`, `&&`, `||`, `not()`.",
        "when = \"$0\"",
    ),
    snippetItem(
        "rules",
        CompletionItemKind.Property,
        "List<String>",
        "Rule IDs this policy applies to. Omit to apply to all configured rules.",
        "rules = listOf(\"$0\")",
    ),
    snippetItem(
        "enabled",
        CompletionItemKind.Property,
        DETAIL_BOOLEAN,
        "Enable or disable the affected rules when the condition matches.",
        "enabled = $0",
    ),
    snippetItem(
        "severity",
        CompletionItemKind.Property,
        DETAIL_STRING,
        "Override severity for all findings from affected rules. One of: error, warning, info.",
        "severity = \"$0\"",
    ),
    snippetItem(
        "override",
        CompletionItemKind.Keyword,
        DETAIL_BLOCK,
        "Config key-value overrides deep-merged into each affected rule's config when the condition matches.",
        "override {\n\t$0\n}",
    ),
)

/**
 * Execution-meta completions always available inside any rule config block.
 * These are not part of [io.karandash.api.Module.configSchema] but are always valid per-rule keys.
 */
internal val RULE_META_COMPLETIONS: List<CompletionItem> = listOf(
    snippetItem(
        "when",
        CompletionItemKind.Property,
        "String | Block",
        "Conditional expression that gates rule execution. " +
            "String form: `when = \"is_draft\"`. " +
            "Block form: `when { expression = \"...\"; override { ... } }`.",
        "when = \"$0\"",
    ),
    snippetItem(
        "priority",
        CompletionItemKind.Property,
        "Int",
        "Rule execution priority group (1–999, default 100). Lower runs first.",
        "priority = $0",
    ),
    snippetItem(
        "bailOnFail",
        CompletionItemKind.Property,
        DETAIL_BOOLEAN,
        "Stop lower-priority groups when this rule produces an ERROR finding.",
        "bailOnFail = $0",
    ),
)
