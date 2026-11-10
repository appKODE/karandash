package io.karandash.loader.bridge

import kotlinx.serialization.Serializable

/** C type of a native ABI return value. [BOOL] is an `int32_t` where 1 means success/true. */
@Serializable
internal enum class CType(
    val c: String,
) {
    STR("const char*"),
    INT("int32_t"),
    BOOL("int32_t"),
    VOID("void"),
}

/**
 * One guest operation of the plugin ABI, shared by every bridge runtime.
 *
 * @property op logical name the bridge modules pass to [BridgeTransport]; a property is a zero-arg op.
 * @property symbol native (C ABI) export name.
 * @property params C parameter names in order; a `#` prefix marks an `int32_t` parameter, every other
 *   parameter is a UTF-8 `const char*`. The WASM ABI passes every parameter as a (ptr, len) string.
 * @property wasm WASM export name.
 * @property js JS plugin member name (a function, or a plain field for zero-arg ops).
 * @property aliases other logical names answered by this op (e.g. the per-type priority properties).
 * @property covers SDK interface members implemented by this op whose name differs (`Interface.member`) or whose
 *   parameter count differs (`Interface.member/arity`: the host fills or packs the other parameters).
 * @property section header section the op is listed under.
 * @property live the answer changes while the module runs (a counter, a cache state), so a transport never
 *   answers it from a property snapshot.
 */
@Serializable
internal data class BridgeOp(
    val op: String,
    val symbol: String,
    val params: List<String> = emptyList(),
    val returns: CType = CType.STR,
    val wasm: String = symbol,
    val js: String = op,
    val aliases: List<String> = emptyList(),
    val covers: List<String> = emptyList(),
    val section: String = "",
    val live: Boolean = false,
)

/**
 * The canonical guest operation table. Transports dispatch through it, the native header
 * (`docs/native-sdk/karandash_plugin.h`) and the WASM name table are generated from it, and the parity
 * test checks it against the SDK `.api` dumps.
 */
internal object BridgeAbi {
    private const val P = "project_id"
    private const val M = "mr_id"
    private const val MR = "mr_json"
    private const val CFG = "config_json"
    private const val RULE = "rule_id"
    private const val ENV = "env_json"
    private const val THREAD = "thread_id"
    private const val CTX = "context_json"
    private const val REQ = "request_json"
    private const val MARKER = "marker"
    private const val FINDINGS = "findings_json"
    private const val URL = "url"
    private const val PFX = "karandash_"
    private const val AI = "karandash_ai_"
    private const val WASM_MUTATOR = "karandash_mutator_"

    private val identity = section(
        "Identity and metadata",
        BridgeOp("id", PFX + "module_id", covers = listOf("Plugin.id")),
        BridgeOp("name", PFX + "module_name"),
        BridgeOp("version", PFX + "module_version"),
        BridgeOp("type", PFX + "module_type"),
        BridgeOp("description", PFX + "module_description"),
        BridgeOp("describe", PFX + "describe"),
        BridgeOp("minApiLevel", PFX + "min_api_level", returns = CType.INT),
        BridgeOp(
            "defaultPriority",
            PFX + "default_priority",
            returns = CType.INT,
            aliases = BridgeConstants.PRIORITY_PROPERTIES.filter { it != "defaultPriority" },
            covers = listOf(
                "AiInterceptorModule.interceptorPriority",
                "AiPromptProcessorModule.processorPriority",
                "AiResponseTransformerModule.transformerPriority",
                "AiContextEnricherModule.enricherPriority",
                "AiRedactorModule.redactorPriority",
            ),
        ),
        BridgeOp("isDefaultEnabled", PFX + "is_default_enabled", returns = CType.BOOL),
        BridgeOp("defaultConfig", PFX + "default_config"),
        BridgeOp("configSchema", PFX + "config_schema"),
        BridgeOp("supportedEnvVars", PFX + "supported_env_vars"),
        BridgeOp("missingAuthMessage", PFX + "missing_auth_message"),
        BridgeOp("messages", PFX + "messages"),
        BridgeOp("preserveKeys", PFX + "preserve_keys"),
    )

    private val lifecycle = section(
        "Lifecycle",
        BridgeOp("initialize", PFX + "initialize", listOf(CTX), CType.BOOL),
        BridgeOp("shutdown", PFX + "shutdown", returns = CType.VOID),
    )

    private val rule = section(
        "RULE",
        BridgeOp("contributedRules", PFX + "contributed_rules"),
        BridgeOp("validate", PFX + "validate", listOf(RULE, MR, CFG)),
        BridgeOp("ruleExplanation", PFX + "rule_explanation", listOf(RULE)),
        BridgeOp("dependsOn", PFX + "depends_on"),
        // FileGranularRuleModule / FindingsAwareRuleModule: exporting one makes the host serve that interface
        BridgeOp("validateFile", PFX + "validate_file", listOf(RULE, "file_json", MR, CFG)),
        BridgeOp("validateWithFindings", PFX + "validate_with_findings", listOf(RULE, MR, CFG, CTX)),
        // a ModuleUsage object, a JSON MergeRequestField name list, a JSON number, a line the host logs at info
        BridgeOp("usage", PFX + "usage", live = true),
        BridgeOp(
            "requiredMergeRequestFields",
            PFX + "required_merge_request_fields",
            listOf(RULE, CFG),
            covers = listOf("RuleModule.requiredMergeRequestFields/1"),
        ),
        BridgeOp("tokensConsumed", PFX + "tokens_consumed", live = true),
        BridgeOp("logCostSummary", PFX + "log_cost_summary", live = true),
    )

    private val reporter = section(
        "REPORTER",
        BridgeOp("formatId", PFX + "format_id", covers = listOf("ReporterModule.formatId", "FormatModule.formatId")),
        BridgeOp("report", PFX + "report", listOf(FINDINGS, "#passed", CFG)),
        BridgeOp("reportEnriched", PFX + "report_enriched", listOf("report_json", CFG)),
        BridgeOp("runtimeReporterConfig", PFX + "runtime_reporter_config", listOf("reporters_config_json", "cli_params_json")),
    )

    private val platform = section(
        "PLATFORM_SERVICE",
        BridgeOp("fetchMergeRequest", PFX + "fetch_merge_request", listOf(P, M)),
        BridgeOp("fetchMergeRequestByUrl", PFX + "fetch_merge_request_by_url", listOf(URL)),
        BridgeOp("detectCiEnvironment", PFX + "detect_ci_environment", listOf(ENV)),
        BridgeOp("parseMergeRequestUrl", PFX + "parse_merge_request_url", listOf(URL)),
        BridgeOp("parseGitRemoteUrl", PFX + "parse_git_remote_url", listOf(URL)),
        BridgeOp("defaultApiUrl", PFX + "default_api_url"),
        BridgeOp("authTokenHint", PFX + "auth_token_hint"),
        BridgeOp("aliases", PFX + "aliases"),
        BridgeOp("addLabels", PFX + "add_labels", listOf(P, M, "labels"), CType.BOOL),
        BridgeOp("removeLabels", PFX + "remove_labels", listOf(P, M, "labels"), CType.BOOL),
        BridgeOp("setDraft", PFX + "set_draft", listOf(P, M, "#draft"), CType.BOOL),
        mutatorOp("updateTitle", "update_title", listOf(P, M, "title")),
        mutatorOp("updateDescription", "update_description", listOf(P, M, "description")),
        mutatorOp("approve", "approve", listOf(P, M)),
        mutatorOp("unapprove", "unapprove", listOf(P, M)),
        BridgeOp("postComment", PFX + "post_comment", listOf(P, M, "body")),
        BridgeOp("updateComment", PFX + "update_comment", listOf(P, M, "comment_id", "body"), CType.BOOL),
        BridgeOp("findExistingComment", PFX + "find_existing_comment", listOf(P, M, MARKER)),
        BridgeOp(
            "postInlineComment",
            PFX + "post_inline_comment",
            listOf(P, M, "body", "file_path", "#line", "diff_refs_json", "#start_line"),
        ),
        BridgeOp("renderSuggestionBlock", PFX + "render_suggestion_block", listOf("suggested_code")),
        BridgeOp("deleteMarkedInlineComments", PFX + "delete_marked_inline_comments", listOf(P, M, MARKER), CType.INT),
        BridgeOp("resolveMarkedInlineComments", PFX + "resolve_marked_inline_comments", listOf(P, M, MARKER), CType.INT),
        BridgeOp("findAllMatchingComments", PFX + "find_all_matching_comments", listOf(P, M, MARKER)),
        // JSON string arrays; a null result keeps the SDK's "no signal" null
        BridgeOp("listOpenMergeRequestIds", PFX + "list_open_merge_request_ids", listOf(P, "target_branch", "#limit")),
        BridgeOp("listDismissedInlineComments", PFX + "list_dismissed_inline_comments", listOf(P, M, MARKER)),
        BridgeOp("deleteComment", PFX + "delete_comment", listOf(P, M, "comment_id"), CType.BOOL),
    )

    private val platformQueries = section(
        "PLATFORM_SERVICE queries and hints (JSON; NULL = the SDK default)",
        BridgeOp("checkAuth", PFX + "check_auth", live = true),
        BridgeOp("fetchFileBlame", PFX + "fetch_file_blame", listOf(P, "file_path", "ref")),
        BridgeOp("fetchFileContent", PFX + "fetch_file_content", listOf(P, "ref", "path")),
        BridgeOp("supportsFileContent", PFX + "supports_file_content", returns = CType.BOOL),
        // query_json: {"text":...,"author":...}
        BridgeOp(
            "searchMergeRequests",
            PFX + "search_merge_requests",
            listOf(P, "query_json", "#limit"),
            covers = listOf("PlatformServiceModule.searchMergeRequests/4"),
        ),
        BridgeOp("isProjectMember", PFX + "is_project_member", listOf(P, "username")),
        BridgeOp("currentUsername", PFX + "current_username", live = true),
        BridgeOp("fetchApprovalSignals", PFX + "fetch_approval_signals", listOf(P, M)),
        BridgeOp("rateLimitStatus", PFX + "rate_limit_status", live = true),
        BridgeOp("permissionScope", PFX + "permission_scope"),
        BridgeOp("tokenLocationHint", PFX + "token_location_hint"),
        BridgeOp("notFoundGuidance", PFX + "not_found_guidance"),
        BridgeOp("unavailableMergeRequestFields", PFX + "unavailable_merge_request_fields"),
        BridgeOp("detectConfidence", PFX + "detect_confidence", listOf(ENV)),
        // absent = false; operation is a PlatformOperation name
        BridgeOp("supports", PFX + "supports", listOf("operation"), CType.BOOL),
        BridgeOp(
            "resolveMarkedInlineCommentsForFiles",
            PFX + "resolve_marked_inline_comments_for_files",
            listOf(P, M, MARKER, "file_paths_json"),
            CType.INT,
        ),
        BridgeOp("listReviewThreads", PFX + "list_review_threads", listOf(P, M)),
    )

    private val platformResults = section(
        "PLATFORM_SERVICE results: {\"ok\":true}, {\"err\":msg} or {\"err\":msg,\"unsupported\":true}",
        BridgeOp("addReviewers", PFX + "add_reviewers", listOf(P, M, "usernames_json")),
        BridgeOp("removeReviewers", PFX + "remove_reviewers", listOf(P, M, "usernames_json")),
        BridgeOp("mergeMr", PFX + "merge_mr", listOf(P, M, "options_json")),
        BridgeOp("closeMr", PFX + "close_mr", listOf(P, M)),
        BridgeOp("reopenMr", PFX + "reopen_mr", listOf(P, M)),
        // note "" = none
        BridgeOp("resolveThread", PFX + "resolve_thread", listOf(P, M, THREAD, "note")),
        BridgeOp("replyToThread", PFX + "reply_to_thread", listOf(P, M, THREAD, "body")),
        BridgeOp("updateNote", PFX + "update_note", listOf(P, M, THREAD, "note_id", "body")),
    )

    private val configFormat = section(
        "CLI_COMMAND and CONFIG_FORMAT",
        BridgeOp("cliCommands", PFX + "cli_commands"),
        // `{"exitCode":n,"stdout":[lines],"stderr":[lines]}`; absent = the commands are listed but not runnable
        BridgeOp("runCliCommand", PFX + "run_cli_command", listOf("command", "args_json")),
        BridgeOp("configFormatId", PFX + "config_format_id", covers = listOf("ConfigFormatModule.formatId")),
        BridgeOp(
            "configFormatExtensions",
            PFX + "config_format_extensions",
            js = "fileExtensions",
            covers = listOf("ConfigFormatModule.fileExtensions"),
        ),
        BridgeOp(
            "configParse",
            PFX + "config_parse",
            listOf("content"),
            js = "parseToMap",
            covers = listOf("ConfigFormatModule.parseToJsonElement"),
        ),
        BridgeOp(
            "configParseExtends",
            PFX + "config_parse_extends",
            listOf("content"),
            js = "parseExtendsOnly",
            covers = listOf("ConfigFormatModule.parseExtendsOnly"),
        ),
    )

    private val profile = section(
        "PROFILE",
        BridgeOp("profileId", PFX + "profile_id"),
        BridgeOp("summary", PFX + "profile_summary"),
        BridgeOp("tags", PFX + "profile_tags"),
        BridgeOp("ruleConfigs", PFX + "profile_rule_configs"),
        BridgeOp("reporterConfigs", PFX + "profile_reporter_configs"),
        BridgeOp("defaultSeverity", PFX + "profile_default_severity"),
        BridgeOp("isDefaultZeroConfigProfile", PFX + "profile_is_default_zero_config", returns = CType.BOOL),
    )

    private val enrichment = section(
        "ENRICHMENT_PROVIDER",
        BridgeOp("enrichmentIds", PFX + "enrichment_ids", covers = listOf("EnrichmentProviderModule.providedEnrichment")),
        BridgeOp("enrich", PFX + "enrichment_enrich", listOf(MR, CFG), covers = listOf("EnrichmentProviderModule.enrich")),
        BridgeOp("skipReason", PFX + "enrichment_skip_reason", live = true),
    )

    private val script = section(
        "SCRIPT",
        BridgeOp("execute", PFX + "execute", listOf("scripts_json", MR, CTX), wasm = PFX + "execute_scripts", js = "executeScripts"),
    )

    private val format = section(
        "FORMAT",
        BridgeOp("format", PFX + "format", listOf("report_json", CFG)),
    )

    private val output = section(
        "OUTPUT (returns NULL on success, else an error message)",
        BridgeOp("outputId", PFX + "output_id"),
        BridgeOp("output", PFX + "output", listOf("formatted_report_json", CFG)),
    )

    private val pipeline = section(
        "PIPELINE_EXTENSION",
        BridgeOp("onConfigured", PFX + "on_configured", listOf(ENV), CType.VOID),
        BridgeOp("onPostFetch", PFX + "on_post_fetch", listOf(MR, CFG), CType.VOID),
        BridgeOp("afterValidation", PFX + "after_validation", listOf(FINDINGS, MR, CFG)),
        BridgeOp("beforeReporting", PFX + "before_reporting", listOf(FINDINGS, MR)),
        BridgeOp("onPipelineComplete", PFX + "on_pipeline_complete", returns = CType.VOID),
        BridgeOp("invalidateRules", PFX + "invalidate_rules", listOf("rule_ids_json"), CType.VOID),
        BridgeOp("computeRuleModuleConfig", PFX + "compute_rule_module_config", listOf(CFG)),
        BridgeOp("costSummary", PFX + "cost_summary", live = true),
        BridgeOp("fetchOptions", PFX + "fetch_options", listOf(CFG)),
        BridgeOp("excludedRules", PFX + "excluded_rules", listOf(CFG, "rule_ids_json")),
        // incremental cache; NULL = no cache
        BridgeOp("cachedFindings", PFX + "cached_findings", listOf(MR, CFG), covers = listOf("PipelineExtension.cachedFindings/3")),
        BridgeOp("saveIncrementalState", PFX + "save_incremental_state", listOf(MR, FINDINGS), CType.VOID),
        BridgeOp("incrementalChangedFiles", PFX + "incremental_changed_files", live = true),
        BridgeOp("incrementalCachedFindings", PFX + "incremental_cached_findings", live = true),
    )

    private val ai = section(
        "AI_*",
        BridgeOp("providerId", AI + "provider_id"),
        BridgeOp("defaultModel", AI + "default_model"),
        BridgeOp("apiKeyEnvVar", AI + "api_key_env_var"),
        BridgeOp("baseUrlEnvVar", AI + "base_url_env_var"),
        BridgeOp("complete", AI + "complete", listOf(REQ)),
        BridgeOp("aiIntercept", AI + "intercept", listOf(REQ, CTX), js = "intercept", covers = listOf("AiInterceptorModule.intercept")),
        BridgeOp("analyzerId", AI + "analyzer_id"),
        BridgeOp("featureKey", AI + "feature_key"),
        BridgeOp("defaultPrompt", AI + "default_prompt"),
        BridgeOp("enabledByDefault", AI + "enabled_by_default", returns = CType.BOOL),
        BridgeOp(
            "aiAnalyze",
            AI + "analyze",
            listOf(MR, "budget_json", "model", CFG),
            js = "analyze",
            covers = listOf("AiAnalyzerModule.analyze"),
        ),
        BridgeOp(
            "aiProcessPrompt",
            AI + "process_prompt",
            listOf("prompt", CTX),
            js = "processPrompt",
            covers = listOf("AiPromptProcessorModule.processPrompt"),
        ),
        BridgeOp(
            "aiProcessSystemPrompt",
            AI + "process_system_prompt",
            listOf("system_prompt", CTX),
            js = "processSystemPrompt",
            covers = listOf("AiPromptProcessorModule.processSystemPrompt"),
        ),
        BridgeOp(
            "aiTransformResponse",
            AI + "transform_response",
            listOf("response_json", REQ, CTX),
            js = "transformResponse",
            covers = listOf("AiResponseTransformerModule.transform"),
        ),
        BridgeOp(
            "aiEnrichContext",
            AI + "enrich_context",
            listOf(MR, CTX),
            js = "enrichContext",
            covers = listOf("AiContextEnricherModule.enrich"),
        ),
        BridgeOp(
            "aiProvideBlock",
            AI + "provide_block",
            listOf(MR, "changes_json", CFG, "budget_json"),
            js = "provideBlock",
            covers = listOf("AiContextEnricherModule.provideBlock"),
        ),
        BridgeOp("aiRedact", AI + "redact", listOf("text", CTX), js = "redact", covers = listOf("AiRedactorModule.redact")),
        BridgeOp(
            "aiAllocateTokens",
            AI + "allocate_tokens",
            listOf("rules_json", "#total_budget", "priorities_json"),
            js = "allocate",
            covers = listOf("AiTokenAllocatorModule.allocate"),
        ),
        BridgeOp("reportId", AI + "report_id"),
        BridgeOp("reportName", AI + "report_name"),
        BridgeOp("generate", AI + "report_generate", listOf(CTX)),
        BridgeOp("metricId", AI + "metric_id"),
        BridgeOp("metricName", AI + "metric_name"),
        BridgeOp("unit", AI + "metric_unit"),
        BridgeOp("compute", AI + "metric_compute", listOf(CTX)),
    )

    val OPS: List<BridgeOp> =
        identity + lifecycle + rule + reporter + platform + platformQueries + platformResults +
            configFormat + profile + enrichment + script +
            format +
            output +
            pipeline + ai

    private val byName: Map<String, BridgeOp> =
        OPS.flatMap { op -> (listOf(op.op) + op.aliases).map { it to op } }.toMap()

    /** The op answering [opOrAlias], or null when no runtime can serve it. */
    fun find(opOrAlias: String): BridgeOp? = byName[opOrAlias]

    /** Converts a transport argument for an `int32_t` parameter (`"true"`/`"false"` map to 1/0). */
    fun intArg(value: String): Int = value.toIntOrNull() ?: if (value == "true") 1 else 0

    /** A BOOL mutator op whose WASM export carries the `karandash_mutator_` prefix. */
    private fun mutatorOp(
        op: String,
        snake: String,
        params: List<String>,
    ) = BridgeOp(op, PFX + snake, params, CType.BOOL, wasm = WASM_MUTATOR + snake)

    private fun section(
        title: String,
        vararg ops: BridgeOp,
    ): List<BridgeOp> = ops.map { it.copy(section = title) }
}
