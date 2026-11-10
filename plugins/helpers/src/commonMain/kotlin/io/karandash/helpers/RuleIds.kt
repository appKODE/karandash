package io.karandash.helpers

/**
 * Rule IDs that plugins in another tier refer to. A plugin cannot depend on a plugin in another tier
 * or on `core/config`, so the owning module and the referring plugin both use the constant here.
 * The CLI keeps its own copy in `ModuleIds`.
 */
object RuleIds {
    /** `ApprovalRuleModule` (plugins-extended); the AI correlation patterns check for its absence. */
    const val APPROVAL = "approval"
}
