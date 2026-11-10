package io.karandash.sdk.typed

import kotlin.jvm.JvmInline

/**
 * Type-safe wrapper for rule identifiers.
 *
 * Prevents accidentally passing a module ID where a rule ID is expected.
 * SDK interfaces still accept [String]; use this class in new code that benefits
 * from compile-time distinction between identifier kinds.
 *
 * Example: `RuleId("title-format")`, `RuleId("commit-messages")`
 */
@JvmInline
public value class RuleId(
    public val value: String
) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for module identifiers.
 *
 * Example: `ModuleId("io.karandash.rules.title-format")`
 */
@JvmInline
public value class ModuleId(
    public val value: String
) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for reporter format identifiers.
 *
 * Example: `FormatId("console")`, `FormatId("sarif")`
 */
@JvmInline
public value class FormatId(
    public val value: String
) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for provider identifiers.
 *
 * Example: `ProviderId("gitlab")`, `ProviderId("github")`
 */
@JvmInline
public value class ProviderId(
    public val value: String
) {
    override fun toString(): String = value
}

/** Wraps this string as a [RuleId]. */
public fun String.toRuleId(): RuleId = RuleId(this)

/** Wraps this string as a [ModuleId]. */
public fun String.toModuleId(): ModuleId = ModuleId(this)

/** Wraps this string as a [FormatId]. */
public fun String.toFormatId(): FormatId = FormatId(this)

/** Wraps this string as a [ProviderId]. */
public fun String.toProviderId(): ProviderId = ProviderId(this)
