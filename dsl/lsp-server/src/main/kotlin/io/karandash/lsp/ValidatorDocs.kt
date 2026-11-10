package io.karandash.lsp

/**
 * Fallback hover docs for validator modules.
 *
 * Extracted from [ModuleDocRegistry] to stay within class size limits.
 */
internal object ValidatorDocs {
    /**
     * Fallback validator hover docs for modules that do not yet override
     * [io.karandash.api.Module.hoverDocumentation].
     */
    val FALLBACK_VALIDATOR_DOCS: Map<String, String> =
        CoreValidatorDocs.DOCS + ReviewValidatorDocs.DOCS + QualityValidatorDocs.DOCS + AdvancedValidatorDocs.DOCS
}
