package io.karandash.lsp

/** Fallback hover docs for validators (starting at contextTag). */
internal object AdvancedValidatorDocs {
    val DOCS: Map<String, String> = mapOf(
        "contextTag" to """
            # contextTag

            **Context Tag Validator**

            Validates that MR titles and commit messages include context tags (e.g., [ios], [android]).

            ## Properties
            - `allowed: List<String>` - Allowed context tags
            - `exactlyOne: Boolean` - Require exactly one tag (default: true)
            - `checkMrTitle: Boolean` - Check MR title (default: true)
            - `checkCommitTitles: Boolean` - Check commit titles (default: true)
            - `tagPattern: String` - Regex for tag detection (default: `\[([a-zA-Z][a-zA-Z0-9_]*)\]`)
            - `caseSensitive: Boolean` - Case-sensitive matching (default: false)

            ## Example
            ```
            contextTag {
                allowed = listOf("ios", "android", "core", "web")
                exactlyOne = true
            }
            ```
        """.trimIndent(),
        "crossEntityConsistency" to """
            # crossEntityConsistency

            **Cross-Entity Consistency Validator**

            Checks that task IDs in commit messages are consistent with the MR title/description.

            ## Properties
            - `taskIdPattern: String` - Regex for task IDs (default: `[A-Z]+-\d+`)
            - `commitTasksSubsetOfMr: Boolean` - Commit tasks must be subset of MR tasks (default: true)
            - `changelogConsistency: Boolean` - Check CHANGELOG consistency (default: true)
            - `changelogPrefix: String` - CHANGELOG line prefix (default: `CHANGELOG:`)
            - `mrTaskSource: String` - Where to find MR tasks: title, description, or both (default: title)

            ## Example
            ```
            crossEntityConsistency {
                taskIdPattern = "[A-Z]+-\\d+"
                mrTaskSource = "both"
            }
            ```
        """.trimIndent(),
        "languagePolicy" to """
            # languagePolicy

            **Language Policy Validator**

            Enforces that MR/PR text content uses only allowed Unicode scripts.

            ## Properties
            - `checkMrTitle: Boolean` - Check MR title (default: true)
            - `checkMrDescription: Boolean` - Check description (default: true)
            - `checkCommitTitle: Boolean` - Check commit titles (default: true)
            - `checkCommitDescription: Boolean` - Check commit descriptions (default: true)
            - `checkBranchName: Boolean` - Check branch name (default: true)
            - `allowedScripts: List<String>` - Allowed scripts: latin, cyrillic, greek, arabic, cjk, hebrew, devanagari
            - `additionalAllowedRanges: List<String>` - Custom Unicode ranges (e.g., 0400-04FF)
            - `languageName: String` - Language name for error messages (default: English)

            ## Example
            ```
            languagePolicy {
                allowedScripts = listOf("latin", "cyrillic")
                languageName = "English/Russian"
            }
            ```
        """.trimIndent(),
        "commitBody" to """
            # commitBody

            **Commit Body Validator**

            Validates that commit bodies match a required pattern.

            ## Properties
            - `pattern: String` - Regex pattern for commit body
            - `required: Boolean` - Whether commit body is mandatory (default: false)
            - `matchMode: String` - How to match: fullMatch, contains, anyLine (default: fullMatch)

            ## Example
            ```
            commitBody {
                required = true
                pattern = ".*Signed-off-by:.*"
                matchMode = "anyLine"
            }
            ```
        """.trimIndent(),
        "jira" to """
            # jira

            **Jira Integration**

            Configures connection to Jira for ticket validation and enrichment.

            ## Properties
            - `baseUrl: String` - Jira base URL
            - `authType: String` - Authentication type: token or basic (default: token)
            - `username: String` - Username for basic auth
            - `password: String` - Password for basic auth
            - `token: String` - API token

            ## Example
            ```
            jira {
                baseUrl = "https://jira.example.com"
                token = env.JIRA_TOKEN
            }
            ```
        """.trimIndent(),
        "ai" to """
            # ai

            **AI-Powered Rules**

            Configures AI-powered validation rules using LLM providers (Anthropic, OpenAI, Ollama).

            ## Properties
            - `provider: String` - LLM provider: openai, anthropic, ollama, ... (default: openai)
            - `apiKey: String` - API key, usually `${'$'}{ENV_VAR}`
            - `model: String` - Model name (optional)
            - `failOnError: Boolean` - Fail the run when an AI call fails (default: false)
            - `maxTokenBudget: Int` - Token budget per run
            - `strictness: String` - Review strictness (default: standard)
            - `concurrency: Int` - Parallel AI calls
            - `reviewMode: String` - Which diff the review sees
            - `modelFallback: List<String>` - Models tried in order when the first fails
            - `consensusModels: List<String>` - Models whose findings are combined

            ## Example
            ```
            ai {
                provider = "anthropic"
                model = "claude-sonnet-4-20250514"
            }
            ```
        """.trimIndent(),
    )
}
