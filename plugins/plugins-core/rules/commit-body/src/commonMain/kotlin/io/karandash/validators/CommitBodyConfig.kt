package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommitBodyConfig(
    val pattern: String? = null,
    val required: Boolean = false,
    @SerialName("requiredWhenSubjectMatches")
    val requiredWhenSubjectMatches: String? = null,
    @SerialName("requiredWhenTitleMatches")
    val requiredWhenTitleMatches: String? = null,
    val matchMode: String = DEFAULT_MATCH_MODE,
    val scope: String = DEFAULT_SCOPE,
) : RuleConfig {
    init {
        require(matchMode in VALID_MATCH_MODES) {
            "Invalid matchMode '$matchMode': must be one of $VALID_MATCH_MODES"
        }
        require(scope in VALID_SCOPES) {
            "Invalid scope '$scope': must be one of $VALID_SCOPES"
        }
    }

    companion object {
        private const val DEFAULT_MATCH_MODE = "fullMatch"
        private val VALID_MATCH_MODES = setOf("fullMatch", "contains", "anyLine")
        const val SCOPE_PER_COMMIT = "perCommit"
        const val SCOPE_ANY_COMMIT = "anyCommit"
        private const val DEFAULT_SCOPE = SCOPE_PER_COMMIT
        private val VALID_SCOPES = setOf(SCOPE_PER_COMMIT, SCOPE_ANY_COMMIT)
    }
}
