package io.karandash.models.finding

import kotlinx.serialization.Serializable

/**
 * Categorizes findings by their domain concern.
 *
 * Categories help reporters group findings meaningfully
 * and enable category-based filtering in policies.
 */
@Serializable
enum class FindingCategory {
    STYLE,
    CONVENTION,
    SECURITY,
    PERFORMANCE,
    CORRECTNESS,
    DOCUMENTATION,
    DEPENDENCY,
    CI_CD,
    WORKFLOW,
}
