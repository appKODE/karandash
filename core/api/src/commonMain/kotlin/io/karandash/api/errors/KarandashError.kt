package io.karandash.api.errors

/**
 * Central enum of all known Karandash error codes.
 *
 * Each entry carries full metadata (title, category, severity, message template,
 * suggestion, docs URL). [ErrorCatalog] stays as a compatibility facade that delegates to these entries;
 * new code uses this enum directly.
 *
 * @property code The short error code string (e.g. "E0001")
 * @property title Human-readable error title
 * @property category The error category
 * @property severity The error severity
 * @property messageTemplate Template with `{param}` placeholders
 * @property suggestion Optional fix suggestion shown to the user
 * @property docsUrl Optional documentation URL
 */
public enum class KarandashError(
    public val code: String,
    public val title: String,
    public val category: ErrorCategory,
    public val severity: ErrorSeverity,
    public val messageTemplate: String,
    public val suggestion: String?,
    public val docsUrl: String?
) {
    UNCLOSED_STRING_LITERAL(
        code = "E0001",
        title = "Unclosed string literal",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Unclosed string literal in {field} value.",
        suggestion = "Add closing \" at the end of the string.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0001"
    ),
    UNKNOWN_RULE_NAME(
        code = "E0002",
        title = "Unknown rule name",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Unknown rule '{rule}' in configuration.",
        suggestion = "Did you mean '{suggestion}'? Check available rules in documentation.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0002"
    ),
    INVALID_REGEX_PATTERN(
        code = "E0003",
        title = "Invalid regex pattern",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Invalid regex pattern in {field}: {error}",
        suggestion = "Verify regex syntax. Common issues: unescaped special characters, unclosed groups.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0003"
    ),
    MISSING_REQUIRED_FIELD(
        code = "E0004",
        title = "Missing required field",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Missing required field '{field}' in {context}.",
        suggestion = "Add the '{field}' field to your configuration.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0004"
    ),
    CONFIG_FILE_NOT_FOUND(
        code = "E0005",
        title = "Config file not found",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Configuration file not found at {path}.",
        suggestion = "Run 'karandash init' to create a new configuration file.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0005"
    ),
    INVALID_YAML_SYNTAX(
        code = "E0006",
        title = "Invalid YAML syntax",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Invalid YAML syntax: {error}",
        suggestion = "Check YAML formatting, indentation, and special characters.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0006"
    ),
    UNKNOWN_YAML_CONFIG_KEY(
        code = "E0007",
        title = "Unknown YAML config key",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Unknown configuration key '{key}'.",
        suggestion = null,
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0007"
    ),
    INVALID_CONFIG_VALUE_TYPE(
        code = "E0008",
        title = "Invalid config value type",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Invalid value type for '{key}': expected {expected}, got {actual}.",
        suggestion = "Verify the value type matches the expected type for this configuration key.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0008"
    ),
    REMOTE_CONFIG_FETCH_FAILED(
        code = "E0009",
        title = "Remote config fetch failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to fetch remote configuration from {url}: {error}",
        suggestion = "Verify the URL is accessible and the remote config file exists.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0009"
    ),
    CIRCULAR_CONFIG_INHERITANCE(
        code = "E0010",
        title = "Circular config inheritance",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Circular inheritance detected: {chain}",
        suggestion = "Remove circular references in 'extends' declarations.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0010"
    ),
    CONFIG_INHERITANCE_DEPTH_EXCEEDED(
        code = "E0011",
        title = "Config inheritance depth exceeded",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Configuration inheritance depth exceeded maximum of {max}.",
        suggestion = "Reduce the number of nested 'extends' declarations.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0011"
    ),
    PR_URL_PARSE_FAILED(
        code = "E0012",
        title = "PR URL parse failed",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to parse PR/MR URL: {url}",
        suggestion = "Verify the URL format matches the expected pattern for your platform.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0012"
    ),
    CONFIG_FETCH_AUTH_FAILED(
        code = "E0013",
        title = "Config fetch authentication failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Authentication failed when fetching config from {url}: {error}",
        suggestion = "Verify the auth credentials in your extends block. Use bearer-token, basic-auth, or custom-header.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0013"
    ),
    TOO_MANY_EXTENDS_ENTRIES(
        code = "E0014",
        title = "Too many extends entries",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Too many entries in extends array: {count} (max: {max}).",
        suggestion = "Reduce the number of extends entries to at most {max}. Consider consolidating shared configs.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0014"
    ),
    GLOB_EXPANSION_FAILED(
        code = "E0015",
        title = "Glob expansion failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to expand glob pattern '{pattern}': {error}",
        suggestion = "Verify the glob pattern is valid and the target directory exists.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0015"
    ),
    CONDITION_EVALUATION_FAILED(
        code = "E0016",
        title = "Condition evaluation failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to evaluate extends condition '{condition}': {error}",
        suggestion = "Check the condition syntax. Supported: env var comparison, platform, &&, ||, !",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0016"
    ),
    EXTENDS_MANIFEST_NOT_FOUND(
        code = "E0017",
        title = "Extends manifest not found",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Extends manifest file not found at {path}",
        suggestion = "Create .karandash/extends-manifest.yaml or remove @name references from extends.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0017"
    ),
    MANIFEST_SET_NOT_FOUND(
        code = "E0018",
        title = "Manifest set not found",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Extends set '@{name}' not found in manifest. Available: {available}",
        suggestion = "Check the set name in .karandash/extends-manifest.yaml.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0018"
    ),
    CACHE_OPERATION_FAILED(
        code = "E0019",
        title = "Cache operation failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.WARNING,
        messageTemplate = "Cache {operation} failed for {url}: {error}. Continuing without cache.",
        suggestion = "Check permissions on the ~/.karandash/cache/ directory (or the configured cache dir).",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0019"
    ),
    GLOB_SECURITY_VIOLATION(
        code = "E0020",
        title = "Glob security violation",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Glob pattern '{pattern}' violates security constraints: {reason}",
        suggestion = "Glob patterns must resolve within the project root. Max expansion: 10 files.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0020"
    ),
    RESTRICTED_ENV_VAR_IN_CONFIG(
        code = "E0021",
        title = "Restricted env var in config",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Restricted environment variable(s) in 'env:' block: {vars}.",
        suggestion = "Remove system variables from the env block. Only declare project-specific variables.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0021"
    ),
    AUTH_MISSING(
        code = "E0100",
        title = "Authentication missing",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Authentication token not found for {platform}.",
        suggestion = "Set {envVar} environment variable or configure auth in .karandash/config.kts",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0100"
    ),
    AUTH_FAILED(
        code = "E0101",
        title = "Authentication failed",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Authentication failed for {platform}: {error}",
        suggestion = "Verify your token has required permissions and is not expired.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0101"
    ),
    UNKNOWN_PLATFORM(
        code = "E0102",
        title = "Unknown platform",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Unknown platform '{platform}'.",
        suggestion = "Supported platforms: github, gitlab, bitbucket, azure-devops, gerrit, gitea, aws-codecommit",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0102"
    ),
    MR_NOT_FOUND(
        code = "E0103",
        title = "MR not found",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Merge request {mrId} not found in project {projectId}.",
        suggestion = "Verify the MR/PR number and project ID are correct.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0103"
    ),
    RATE_LIMITED(
        code = "E0104",
        title = "Rate limited",
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.WARNING,
        messageTemplate = "API rate limit exceeded for {platform}. Reset at {resetTime}.",
        suggestion = "Wait until rate limit resets or use a token with higher rate limits.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0104"
    ),
    TITLE_FORMAT_MISMATCH(
        code = "E0200",
        title = "Title format mismatch",
        category = ErrorCategory.VALIDATION,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "MR title does not match required pattern: {pattern}",
        suggestion = "Update title to match format: {example}",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0200"
    ),
    TITLE_LENGTH_VIOLATION(
        code = "E0201",
        title = "Title length violation",
        category = ErrorCategory.VALIDATION,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "MR title length ({length}) violates {constraint} constraint ({limit}).",
        suggestion = "Adjust title to meet length requirements.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0201"
    ),
    MISSING_REQUIRED_LABEL(
        code = "E0202",
        title = "Missing required label",
        category = ErrorCategory.VALIDATION,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "MR is missing required label: {label}",
        suggestion = "Add the '{label}' label to your merge request.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0202"
    ),
    FORBIDDEN_LABEL_PRESENT(
        code = "E0203",
        title = "Forbidden label present",
        category = ErrorCategory.VALIDATION,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "MR has forbidden label: {label}",
        suggestion = "Remove the '{label}' label from your merge request.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0203"
    ),
    MR_TOO_LARGE(
        code = "E0204",
        title = "MR too large",
        category = ErrorCategory.VALIDATION,
        severity = ErrorSeverity.WARNING,
        messageTemplate = "MR exceeds size limit: {size} {unit} (max: {limit} {unit}).",
        suggestion = "Consider splitting this MR into smaller, focused changes.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0204"
    ),
    PLUGIN_LOAD_FAILED(
        code = "E0300",
        title = "Plugin load failed",
        category = ErrorCategory.PLUGIN,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to load plugin '{plugin}': {error}",
        suggestion = "Verify plugin file exists and has correct format.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0300"
    ),
    PLUGIN_INIT_FAILED(
        code = "E0301",
        title = "Plugin init failed",
        category = ErrorCategory.PLUGIN,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Plugin '{plugin}' initialization failed: {error}",
        suggestion = "Check plugin configuration and dependencies.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0301"
    ),
    PLUGIN_NOT_INSTALLED(
        code = "E0302",
        title = "Plugin declared but not installed",
        category = ErrorCategory.PLUGIN,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Plugin '{module}' declared in manifest but not installed.",
        suggestion = "Run 'karandash plugin install --registry <id>' for each declared plugin.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0302"
    ),
    UNKNOWN_MODULE_REFERENCE(
        code = "E0303",
        title = "Unknown module reference in config",
        category = ErrorCategory.PLUGIN,
        severity = ErrorSeverity.WARNING,
        messageTemplate = "Unknown plugin module '{module}' referenced in config section '{section}'.",
        suggestion = "Check spelling or add the plugin to manifest.yaml, then run 'karandash plugin install --registry <id>'.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0303"
    ),
    ENV_VAR_NOT_SET(
        code = "E0022",
        title = "Environment variable not set",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.WARNING,
        messageTemplate = "Environment variable '{var}' is referenced in config but not set.",
        suggestion = "Set the variable in your shell or CI environment, or add a default in the 'env:' block.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0022"
    ),
    EXTERNAL_VALUE_RESOLVE_FAILED(
        code = "E0023",
        title = "External value resolution failed",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Failed to resolve external value from {source}: {error}",
        suggestion = "Verify the file path exists and is readable, or the URL is accessible.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0023"
    ),
    CONFIG_INHERITANCE_CHAIN_TOO_LARGE(
        code = "E0024",
        title = "Config inheritance chain too large",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Config inheritance chain too large (max: {max} nodes)",
        suggestion = "Reduce the number of configs the 'extends' chain pulls in, or raise limits.extends.max-chain-size.",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0024"
    ),
    UNSUPPORTED_CONFIG_FORMAT(
        code = "E0025",
        title = "Unsupported config format",
        category = ErrorCategory.CONFIG,
        severity = ErrorSeverity.ERROR,
        messageTemplate = "Unsupported config format '{format}' for {source}.",
        suggestion = "Use one of the supported config formats (.kts, .yaml, .yml, .json).",
        docsUrl = "https://appkode.github.io/karandash/reference/error-codes/#e0025"
    );

    /**
     * The error code wrapped in brackets, e.g. returns `"\[E0001\]"` for code `"E0001"`.
     */
    public val bracketCode: String get() = "[$code]"

    /**
     * Converts this enum entry to an [ErrorCode] data class instance.
     */
    public fun toErrorCode(): ErrorCode =
        ErrorCode(
            code = code,
            title = title,
            category = category,
            severity = severity,
            messageTemplate = messageTemplate,
            suggestion = suggestion,
            docsUrl = docsUrl
        )

    /**
     * Formats [messageTemplate] by substituting `{key}` placeholders with values from [params].
     *
     * @param params Map of parameter names to values for template substitution
     * @return Formatted message with parameters substituted
     */
    public fun format(params: Map<String, String>): String {
        var message = messageTemplate
        params.forEach { (key, value) ->
            message = message.replace("{$key}", value)
        }
        return message
    }

    public companion object {
        private val byCodeMap: Map<String, KarandashError> by lazy {
            entries.associateBy { it.code }
        }

        /**
         * Look up a [KarandashError] by its code string (e.g. "E0001").
         *
         * @param code The error code string
         * @return The matching enum entry, or null if not found
         */
        public fun fromCode(code: String): KarandashError? = byCodeMap[code]

        /**
         * Get all error entries for a specific [ErrorCategory].
         *
         * @param category The category to filter by
         * @return List of matching entries
         */
        public fun byCategory(category: ErrorCategory): List<KarandashError> = entries.filter { it.category == category }
    }
}
