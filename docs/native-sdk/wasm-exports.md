# WASM export table

Generated from `BridgeAbi.OPS` by `BridgeAbiDocsTest`; do not edit. A string parameter is passed as two
`i32`s (pointer, length) into guest memory written through `karandash_alloc`; a parameter marked `(i32)` is a
plain `i32`. A `STR` result is an `i32` pointer to a 4-byte little-endian length followed by UTF-8 bytes
(0 means none); `INT` and `BOOL` results are `i32`.

| Op | Export | Parameters | Returns | JS member |
|----|--------|------------|---------|-----------|
| `id` | `karandash_module_id` | - | STR | `id` |
| `name` | `karandash_module_name` | - | STR | `name` |
| `version` | `karandash_module_version` | - | STR | `version` |
| `type` | `karandash_module_type` | - | STR | `type` |
| `description` | `karandash_module_description` | - | STR | `description` |
| `describe` | `karandash_describe` | - | STR | `describe` |
| `minApiLevel` | `karandash_min_api_level` | - | INT | `minApiLevel` |
| `defaultPriority` | `karandash_default_priority` | - | INT | `defaultPriority` |
| `isDefaultEnabled` | `karandash_is_default_enabled` | - | BOOL | `isDefaultEnabled` |
| `defaultConfig` | `karandash_default_config` | - | STR | `defaultConfig` |
| `configSchema` | `karandash_config_schema` | - | STR | `configSchema` |
| `supportedEnvVars` | `karandash_supported_env_vars` | - | STR | `supportedEnvVars` |
| `missingAuthMessage` | `karandash_missing_auth_message` | - | STR | `missingAuthMessage` |
| `messages` | `karandash_messages` | - | STR | `messages` |
| `preserveKeys` | `karandash_preserve_keys` | - | STR | `preserveKeys` |
| `initialize` | `karandash_initialize` | context_json | BOOL | `initialize` |
| `shutdown` | `karandash_shutdown` | - | VOID | `shutdown` |
| `contributedRules` | `karandash_contributed_rules` | - | STR | `contributedRules` |
| `validate` | `karandash_validate` | rule_id, mr_json, config_json | STR | `validate` |
| `ruleExplanation` | `karandash_rule_explanation` | rule_id | STR | `ruleExplanation` |
| `dependsOn` | `karandash_depends_on` | - | STR | `dependsOn` |
| `validateFile` | `karandash_validate_file` | rule_id, file_json, mr_json, config_json | STR | `validateFile` |
| `validateWithFindings` | `karandash_validate_with_findings` | rule_id, mr_json, config_json, context_json | STR | `validateWithFindings` |
| `usage` | `karandash_usage` | - | STR | `usage` |
| `requiredMergeRequestFields` | `karandash_required_merge_request_fields` | rule_id, config_json | STR | `requiredMergeRequestFields` |
| `tokensConsumed` | `karandash_tokens_consumed` | - | STR | `tokensConsumed` |
| `logCostSummary` | `karandash_log_cost_summary` | - | STR | `logCostSummary` |
| `formatId` | `karandash_format_id` | - | STR | `formatId` |
| `report` | `karandash_report` | findings_json, passed (i32), config_json | STR | `report` |
| `reportEnriched` | `karandash_report_enriched` | report_json, config_json | STR | `reportEnriched` |
| `runtimeReporterConfig` | `karandash_runtime_reporter_config` | reporters_config_json, cli_params_json | STR | `runtimeReporterConfig` |
| `fetchMergeRequest` | `karandash_fetch_merge_request` | project_id, mr_id | STR | `fetchMergeRequest` |
| `fetchMergeRequestByUrl` | `karandash_fetch_merge_request_by_url` | url | STR | `fetchMergeRequestByUrl` |
| `detectCiEnvironment` | `karandash_detect_ci_environment` | env_json | STR | `detectCiEnvironment` |
| `parseMergeRequestUrl` | `karandash_parse_merge_request_url` | url | STR | `parseMergeRequestUrl` |
| `parseGitRemoteUrl` | `karandash_parse_git_remote_url` | url | STR | `parseGitRemoteUrl` |
| `defaultApiUrl` | `karandash_default_api_url` | - | STR | `defaultApiUrl` |
| `authTokenHint` | `karandash_auth_token_hint` | - | STR | `authTokenHint` |
| `aliases` | `karandash_aliases` | - | STR | `aliases` |
| `addLabels` | `karandash_add_labels` | project_id, mr_id, labels | BOOL | `addLabels` |
| `removeLabels` | `karandash_remove_labels` | project_id, mr_id, labels | BOOL | `removeLabels` |
| `setDraft` | `karandash_set_draft` | project_id, mr_id, draft (i32) | BOOL | `setDraft` |
| `updateTitle` | `karandash_mutator_update_title` | project_id, mr_id, title | BOOL | `updateTitle` |
| `updateDescription` | `karandash_mutator_update_description` | project_id, mr_id, description | BOOL | `updateDescription` |
| `approve` | `karandash_mutator_approve` | project_id, mr_id | BOOL | `approve` |
| `unapprove` | `karandash_mutator_unapprove` | project_id, mr_id | BOOL | `unapprove` |
| `postComment` | `karandash_post_comment` | project_id, mr_id, body | STR | `postComment` |
| `updateComment` | `karandash_update_comment` | project_id, mr_id, comment_id, body | BOOL | `updateComment` |
| `findExistingComment` | `karandash_find_existing_comment` | project_id, mr_id, marker | STR | `findExistingComment` |
| `postInlineComment` | `karandash_post_inline_comment` | project_id, mr_id, body, file_path, line (i32), diff_refs_json, start_line (i32) | STR | `postInlineComment` |
| `renderSuggestionBlock` | `karandash_render_suggestion_block` | suggested_code | STR | `renderSuggestionBlock` |
| `deleteMarkedInlineComments` | `karandash_delete_marked_inline_comments` | project_id, mr_id, marker | INT | `deleteMarkedInlineComments` |
| `resolveMarkedInlineComments` | `karandash_resolve_marked_inline_comments` | project_id, mr_id, marker | INT | `resolveMarkedInlineComments` |
| `findAllMatchingComments` | `karandash_find_all_matching_comments` | project_id, mr_id, marker | STR | `findAllMatchingComments` |
| `listOpenMergeRequestIds` | `karandash_list_open_merge_request_ids` | project_id, target_branch, limit (i32) | STR | `listOpenMergeRequestIds` |
| `listDismissedInlineComments` | `karandash_list_dismissed_inline_comments` | project_id, mr_id, marker | STR | `listDismissedInlineComments` |
| `deleteComment` | `karandash_delete_comment` | project_id, mr_id, comment_id | BOOL | `deleteComment` |
| `checkAuth` | `karandash_check_auth` | - | STR | `checkAuth` |
| `fetchFileBlame` | `karandash_fetch_file_blame` | project_id, file_path, ref | STR | `fetchFileBlame` |
| `fetchFileContent` | `karandash_fetch_file_content` | project_id, ref, path | STR | `fetchFileContent` |
| `supportsFileContent` | `karandash_supports_file_content` | - | BOOL | `supportsFileContent` |
| `searchMergeRequests` | `karandash_search_merge_requests` | project_id, query_json, limit (i32) | STR | `searchMergeRequests` |
| `isProjectMember` | `karandash_is_project_member` | project_id, username | STR | `isProjectMember` |
| `currentUsername` | `karandash_current_username` | - | STR | `currentUsername` |
| `fetchApprovalSignals` | `karandash_fetch_approval_signals` | project_id, mr_id | STR | `fetchApprovalSignals` |
| `rateLimitStatus` | `karandash_rate_limit_status` | - | STR | `rateLimitStatus` |
| `permissionScope` | `karandash_permission_scope` | - | STR | `permissionScope` |
| `tokenLocationHint` | `karandash_token_location_hint` | - | STR | `tokenLocationHint` |
| `notFoundGuidance` | `karandash_not_found_guidance` | - | STR | `notFoundGuidance` |
| `unavailableMergeRequestFields` | `karandash_unavailable_merge_request_fields` | - | STR | `unavailableMergeRequestFields` |
| `detectConfidence` | `karandash_detect_confidence` | env_json | STR | `detectConfidence` |
| `supports` | `karandash_supports` | operation | BOOL | `supports` |
| `resolveMarkedInlineCommentsForFiles` | `karandash_resolve_marked_inline_comments_for_files` | project_id, mr_id, marker, file_paths_json | INT | `resolveMarkedInlineCommentsForFiles` |
| `listReviewThreads` | `karandash_list_review_threads` | project_id, mr_id | STR | `listReviewThreads` |
| `addReviewers` | `karandash_add_reviewers` | project_id, mr_id, usernames_json | STR | `addReviewers` |
| `removeReviewers` | `karandash_remove_reviewers` | project_id, mr_id, usernames_json | STR | `removeReviewers` |
| `mergeMr` | `karandash_merge_mr` | project_id, mr_id, options_json | STR | `mergeMr` |
| `closeMr` | `karandash_close_mr` | project_id, mr_id | STR | `closeMr` |
| `reopenMr` | `karandash_reopen_mr` | project_id, mr_id | STR | `reopenMr` |
| `resolveThread` | `karandash_resolve_thread` | project_id, mr_id, thread_id, note | STR | `resolveThread` |
| `replyToThread` | `karandash_reply_to_thread` | project_id, mr_id, thread_id, body | STR | `replyToThread` |
| `updateNote` | `karandash_update_note` | project_id, mr_id, thread_id, note_id, body | STR | `updateNote` |
| `cliCommands` | `karandash_cli_commands` | - | STR | `cliCommands` |
| `runCliCommand` | `karandash_run_cli_command` | command, args_json | STR | `runCliCommand` |
| `configFormatId` | `karandash_config_format_id` | - | STR | `configFormatId` |
| `configFormatExtensions` | `karandash_config_format_extensions` | - | STR | `fileExtensions` |
| `configParse` | `karandash_config_parse` | content | STR | `parseToMap` |
| `configParseExtends` | `karandash_config_parse_extends` | content | STR | `parseExtendsOnly` |
| `profileId` | `karandash_profile_id` | - | STR | `profileId` |
| `summary` | `karandash_profile_summary` | - | STR | `summary` |
| `tags` | `karandash_profile_tags` | - | STR | `tags` |
| `ruleConfigs` | `karandash_profile_rule_configs` | - | STR | `ruleConfigs` |
| `reporterConfigs` | `karandash_profile_reporter_configs` | - | STR | `reporterConfigs` |
| `defaultSeverity` | `karandash_profile_default_severity` | - | STR | `defaultSeverity` |
| `isDefaultZeroConfigProfile` | `karandash_profile_is_default_zero_config` | - | BOOL | `isDefaultZeroConfigProfile` |
| `enrichmentIds` | `karandash_enrichment_ids` | - | STR | `enrichmentIds` |
| `enrich` | `karandash_enrichment_enrich` | mr_json, config_json | STR | `enrich` |
| `skipReason` | `karandash_enrichment_skip_reason` | - | STR | `skipReason` |
| `execute` | `karandash_execute_scripts` | scripts_json, mr_json, context_json | STR | `executeScripts` |
| `format` | `karandash_format` | report_json, config_json | STR | `format` |
| `outputId` | `karandash_output_id` | - | STR | `outputId` |
| `output` | `karandash_output` | formatted_report_json, config_json | STR | `output` |
| `onConfigured` | `karandash_on_configured` | env_json | VOID | `onConfigured` |
| `onPostFetch` | `karandash_on_post_fetch` | mr_json, config_json | VOID | `onPostFetch` |
| `afterValidation` | `karandash_after_validation` | findings_json, mr_json, config_json | STR | `afterValidation` |
| `beforeReporting` | `karandash_before_reporting` | findings_json, mr_json | STR | `beforeReporting` |
| `onPipelineComplete` | `karandash_on_pipeline_complete` | - | VOID | `onPipelineComplete` |
| `invalidateRules` | `karandash_invalidate_rules` | rule_ids_json | VOID | `invalidateRules` |
| `computeRuleModuleConfig` | `karandash_compute_rule_module_config` | config_json | STR | `computeRuleModuleConfig` |
| `costSummary` | `karandash_cost_summary` | - | STR | `costSummary` |
| `fetchOptions` | `karandash_fetch_options` | config_json | STR | `fetchOptions` |
| `excludedRules` | `karandash_excluded_rules` | config_json, rule_ids_json | STR | `excludedRules` |
| `cachedFindings` | `karandash_cached_findings` | mr_json, config_json | STR | `cachedFindings` |
| `saveIncrementalState` | `karandash_save_incremental_state` | mr_json, findings_json | VOID | `saveIncrementalState` |
| `incrementalChangedFiles` | `karandash_incremental_changed_files` | - | STR | `incrementalChangedFiles` |
| `incrementalCachedFindings` | `karandash_incremental_cached_findings` | - | STR | `incrementalCachedFindings` |
| `providerId` | `karandash_ai_provider_id` | - | STR | `providerId` |
| `defaultModel` | `karandash_ai_default_model` | - | STR | `defaultModel` |
| `apiKeyEnvVar` | `karandash_ai_api_key_env_var` | - | STR | `apiKeyEnvVar` |
| `baseUrlEnvVar` | `karandash_ai_base_url_env_var` | - | STR | `baseUrlEnvVar` |
| `complete` | `karandash_ai_complete` | request_json | STR | `complete` |
| `aiIntercept` | `karandash_ai_intercept` | request_json, context_json | STR | `intercept` |
| `analyzerId` | `karandash_ai_analyzer_id` | - | STR | `analyzerId` |
| `featureKey` | `karandash_ai_feature_key` | - | STR | `featureKey` |
| `defaultPrompt` | `karandash_ai_default_prompt` | - | STR | `defaultPrompt` |
| `enabledByDefault` | `karandash_ai_enabled_by_default` | - | BOOL | `enabledByDefault` |
| `aiAnalyze` | `karandash_ai_analyze` | mr_json, budget_json, model, config_json | STR | `analyze` |
| `aiProcessPrompt` | `karandash_ai_process_prompt` | prompt, context_json | STR | `processPrompt` |
| `aiProcessSystemPrompt` | `karandash_ai_process_system_prompt` | system_prompt, context_json | STR | `processSystemPrompt` |
| `aiTransformResponse` | `karandash_ai_transform_response` | response_json, request_json, context_json | STR | `transformResponse` |
| `aiEnrichContext` | `karandash_ai_enrich_context` | mr_json, context_json | STR | `enrichContext` |
| `aiProvideBlock` | `karandash_ai_provide_block` | mr_json, changes_json, config_json, budget_json | STR | `provideBlock` |
| `aiRedact` | `karandash_ai_redact` | text, context_json | STR | `redact` |
| `aiAllocateTokens` | `karandash_ai_allocate_tokens` | rules_json, total_budget (i32), priorities_json | STR | `allocate` |
| `reportId` | `karandash_ai_report_id` | - | STR | `reportId` |
| `reportName` | `karandash_ai_report_name` | - | STR | `reportName` |
| `generate` | `karandash_ai_report_generate` | context_json | STR | `generate` |
| `metricId` | `karandash_ai_metric_id` | - | STR | `metricId` |
| `metricName` | `karandash_ai_metric_name` | - | STR | `metricName` |
| `unit` | `karandash_ai_metric_unit` | - | STR | `unit` |
| `compute` | `karandash_ai_metric_compute` | context_json | STR | `compute` |

## Host-side names (not guest ops)

| Export | Purpose |
|--------|---------|
| `karandash_abi_version` | version handshake read by the loader before any op |
| `karandash_alloc` | guest allocator used by the string protocol |
| `karandash_dealloc` | guest allocator used by the string protocol |
| `karandash_host_call` | host import `env` (svc_ptr, svc_len, req_ptr, req_len) -> response length |
| `karandash_host_response` | host import `env` (dst_ptr) -> length: copies the pending response, -1 if none |
| `karandash_ai_analyze_continue` | second leg of the WASM AI analyze `aiRequest` handshake |
