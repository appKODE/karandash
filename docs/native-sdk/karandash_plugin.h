/* GENERATED from BridgeAbi.OPS by BridgeAbiDocsTest. Do not edit; see that test to regenerate. */
#ifndef KARANDASH_PLUGIN_H
#define KARANDASH_PLUGIN_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Karandash Native Plugin ABI v2 (v1 plugins, without karandash_set_host, still load).
 * Strings are UTF-8, null-terminated. Complex values are JSON strings.
 * Returned strings are heap-allocated by the plugin and released by the host through
 * karandash_free_string; strings passed to the plugin stay owned by the host.
 * Every op is optional except identity and lifecycle: an absent export means the SDK default.
 *
 * Host services (v2): before karandash_initialize the host calls karandash_set_host(2, host_ctx, call, free);
 * return 1 to accept. call(host_ctx, service, request_json) answers a JSON string the plugin releases
 * with free. Services: log, cache.get, cache.put, resolvePattern, resolveContent, ai.complete, ai.proceed, skip.
 */

typedef char* (*karandash_host_call_fn)(void* host_ctx, const char* service, const char* request_json);
typedef void (*karandash_host_free_fn)(char* ptr);

int32_t karandash_abi_version(void);
int32_t karandash_set_host(int32_t abi, void* host_ctx, karandash_host_call_fn call, karandash_host_free_fn free_fn);
void karandash_free_string(const char* ptr);

/* Identity and metadata */
const char* karandash_module_id(void);
const char* karandash_module_name(void);
const char* karandash_module_version(void);
const char* karandash_module_type(void);
const char* karandash_module_description(void);
const char* karandash_describe(void);
int32_t karandash_min_api_level(void);
int32_t karandash_default_priority(void);
int32_t karandash_is_default_enabled(void);
const char* karandash_default_config(void);
const char* karandash_config_schema(void);
const char* karandash_supported_env_vars(void);
const char* karandash_missing_auth_message(void);
const char* karandash_messages(void);
const char* karandash_preserve_keys(void);

/* Lifecycle */
int32_t karandash_initialize(const char* context_json);
void karandash_shutdown(void);

/* RULE */
const char* karandash_contributed_rules(void);
const char* karandash_validate(const char* rule_id, const char* mr_json, const char* config_json);
const char* karandash_rule_explanation(const char* rule_id);
const char* karandash_depends_on(void);
const char* karandash_validate_file(const char* rule_id, const char* file_json, const char* mr_json, const char* config_json);
const char* karandash_validate_with_findings(const char* rule_id, const char* mr_json, const char* config_json, const char* context_json);
const char* karandash_usage(void);
const char* karandash_required_merge_request_fields(const char* rule_id, const char* config_json);
const char* karandash_tokens_consumed(void);
const char* karandash_log_cost_summary(void);

/* REPORTER */
const char* karandash_format_id(void);
const char* karandash_report(const char* findings_json, int32_t passed, const char* config_json);
const char* karandash_report_enriched(const char* report_json, const char* config_json);
const char* karandash_runtime_reporter_config(const char* reporters_config_json, const char* cli_params_json);

/* PLATFORM_SERVICE */
const char* karandash_fetch_merge_request(const char* project_id, const char* mr_id);
const char* karandash_fetch_merge_request_by_url(const char* url);
const char* karandash_detect_ci_environment(const char* env_json);
const char* karandash_parse_merge_request_url(const char* url);
const char* karandash_parse_git_remote_url(const char* url);
const char* karandash_default_api_url(void);
const char* karandash_auth_token_hint(void);
const char* karandash_aliases(void);
int32_t karandash_add_labels(const char* project_id, const char* mr_id, const char* labels);
int32_t karandash_remove_labels(const char* project_id, const char* mr_id, const char* labels);
int32_t karandash_set_draft(const char* project_id, const char* mr_id, int32_t draft);
int32_t karandash_update_title(const char* project_id, const char* mr_id, const char* title);
int32_t karandash_update_description(const char* project_id, const char* mr_id, const char* description);
int32_t karandash_approve(const char* project_id, const char* mr_id);
int32_t karandash_unapprove(const char* project_id, const char* mr_id);
const char* karandash_post_comment(const char* project_id, const char* mr_id, const char* body);
int32_t karandash_update_comment(const char* project_id, const char* mr_id, const char* comment_id, const char* body);
const char* karandash_find_existing_comment(const char* project_id, const char* mr_id, const char* marker);
const char* karandash_post_inline_comment(const char* project_id, const char* mr_id, const char* body, const char* file_path, int32_t line, const char* diff_refs_json, int32_t start_line);
const char* karandash_render_suggestion_block(const char* suggested_code);
int32_t karandash_delete_marked_inline_comments(const char* project_id, const char* mr_id, const char* marker);
int32_t karandash_resolve_marked_inline_comments(const char* project_id, const char* mr_id, const char* marker);
const char* karandash_find_all_matching_comments(const char* project_id, const char* mr_id, const char* marker);
const char* karandash_list_open_merge_request_ids(const char* project_id, const char* target_branch, int32_t limit);
const char* karandash_list_dismissed_inline_comments(const char* project_id, const char* mr_id, const char* marker);
int32_t karandash_delete_comment(const char* project_id, const char* mr_id, const char* comment_id);

/* PLATFORM_SERVICE queries and hints (JSON; NULL = the SDK default) */
const char* karandash_check_auth(void);
const char* karandash_fetch_file_blame(const char* project_id, const char* file_path, const char* ref);
const char* karandash_fetch_file_content(const char* project_id, const char* ref, const char* path);
int32_t karandash_supports_file_content(void);
const char* karandash_search_merge_requests(const char* project_id, const char* query_json, int32_t limit);
const char* karandash_is_project_member(const char* project_id, const char* username);
const char* karandash_current_username(void);
const char* karandash_fetch_approval_signals(const char* project_id, const char* mr_id);
const char* karandash_rate_limit_status(void);
const char* karandash_permission_scope(void);
const char* karandash_token_location_hint(void);
const char* karandash_not_found_guidance(void);
const char* karandash_unavailable_merge_request_fields(void);
const char* karandash_detect_confidence(const char* env_json);
int32_t karandash_supports(const char* operation);
int32_t karandash_resolve_marked_inline_comments_for_files(const char* project_id, const char* mr_id, const char* marker, const char* file_paths_json);
const char* karandash_list_review_threads(const char* project_id, const char* mr_id);

/* PLATFORM_SERVICE results: {"ok":true}, {"err":msg} or {"err":msg,"unsupported":true} */
const char* karandash_add_reviewers(const char* project_id, const char* mr_id, const char* usernames_json);
const char* karandash_remove_reviewers(const char* project_id, const char* mr_id, const char* usernames_json);
const char* karandash_merge_mr(const char* project_id, const char* mr_id, const char* options_json);
const char* karandash_close_mr(const char* project_id, const char* mr_id);
const char* karandash_reopen_mr(const char* project_id, const char* mr_id);
const char* karandash_resolve_thread(const char* project_id, const char* mr_id, const char* thread_id, const char* note);
const char* karandash_reply_to_thread(const char* project_id, const char* mr_id, const char* thread_id, const char* body);
const char* karandash_update_note(const char* project_id, const char* mr_id, const char* thread_id, const char* note_id, const char* body);

/* CLI_COMMAND and CONFIG_FORMAT */
const char* karandash_cli_commands(void);
const char* karandash_run_cli_command(const char* command, const char* args_json);
const char* karandash_config_format_id(void);
const char* karandash_config_format_extensions(void);
const char* karandash_config_parse(const char* content);
const char* karandash_config_parse_extends(const char* content);

/* PROFILE */
const char* karandash_profile_id(void);
const char* karandash_profile_summary(void);
const char* karandash_profile_tags(void);
const char* karandash_profile_rule_configs(void);
const char* karandash_profile_reporter_configs(void);
const char* karandash_profile_default_severity(void);
int32_t karandash_profile_is_default_zero_config(void);

/* ENRICHMENT_PROVIDER */
const char* karandash_enrichment_ids(void);
const char* karandash_enrichment_enrich(const char* mr_json, const char* config_json);
const char* karandash_enrichment_skip_reason(void);

/* SCRIPT */
const char* karandash_execute(const char* scripts_json, const char* mr_json, const char* context_json);

/* FORMAT */
const char* karandash_format(const char* report_json, const char* config_json);

/* OUTPUT (returns NULL on success, else an error message) */
const char* karandash_output_id(void);
const char* karandash_output(const char* formatted_report_json, const char* config_json);

/* PIPELINE_EXTENSION */
void karandash_on_configured(const char* env_json);
void karandash_on_post_fetch(const char* mr_json, const char* config_json);
const char* karandash_after_validation(const char* findings_json, const char* mr_json, const char* config_json);
const char* karandash_before_reporting(const char* findings_json, const char* mr_json);
void karandash_on_pipeline_complete(void);
void karandash_invalidate_rules(const char* rule_ids_json);
const char* karandash_compute_rule_module_config(const char* config_json);
const char* karandash_cost_summary(void);
const char* karandash_fetch_options(const char* config_json);
const char* karandash_excluded_rules(const char* config_json, const char* rule_ids_json);
const char* karandash_cached_findings(const char* mr_json, const char* config_json);
void karandash_save_incremental_state(const char* mr_json, const char* findings_json);
const char* karandash_incremental_changed_files(void);
const char* karandash_incremental_cached_findings(void);

/* AI_* */
const char* karandash_ai_provider_id(void);
const char* karandash_ai_default_model(void);
const char* karandash_ai_api_key_env_var(void);
const char* karandash_ai_base_url_env_var(void);
const char* karandash_ai_complete(const char* request_json);
const char* karandash_ai_intercept(const char* request_json, const char* context_json);
const char* karandash_ai_analyzer_id(void);
const char* karandash_ai_feature_key(void);
const char* karandash_ai_default_prompt(void);
int32_t karandash_ai_enabled_by_default(void);
const char* karandash_ai_analyze(const char* mr_json, const char* budget_json, const char* model, const char* config_json);
const char* karandash_ai_process_prompt(const char* prompt, const char* context_json);
const char* karandash_ai_process_system_prompt(const char* system_prompt, const char* context_json);
const char* karandash_ai_transform_response(const char* response_json, const char* request_json, const char* context_json);
const char* karandash_ai_enrich_context(const char* mr_json, const char* context_json);
const char* karandash_ai_provide_block(const char* mr_json, const char* changes_json, const char* config_json, const char* budget_json);
const char* karandash_ai_redact(const char* text, const char* context_json);
const char* karandash_ai_allocate_tokens(const char* rules_json, int32_t total_budget, const char* priorities_json);
const char* karandash_ai_report_id(void);
const char* karandash_ai_report_name(void);
const char* karandash_ai_report_generate(const char* context_json);
const char* karandash_ai_metric_id(void);
const char* karandash_ai_metric_name(void);
const char* karandash_ai_metric_unit(void);
const char* karandash_ai_metric_compute(const char* context_json);

#ifdef __cplusplus
}
#endif

#endif /* KARANDASH_PLUGIN_H */
