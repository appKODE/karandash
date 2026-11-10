import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generates a `NativeExports.kt` file that exposes a Karandash module through
 * the C ABI used by [NativePluginLoader].
 *
 * The generated file is the same for every plugin module. The only varying parts are:
 * - [packageName] -- the Kotlin package declaration
 * - [moduleClass] -- the concrete module class instantiated via `by lazy`
 * - [moduleType] -- one of RULE, REPORTER, PLATFORM_SERVICE, AI_ANALYZER
 *
 * Generated guests speak ABI v2: `karandash_set_host` takes the host-service channel ([io.karandash.helpers.CAbiHost]).
 */
abstract class GenerateNativeExportsTask : DefaultTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val moduleClass: Property<String>

    @get:Input
    abstract val moduleType: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val cls = moduleClass.get()
        val type = moduleType.get()
        val pkgDir = pkg.replace('.', '/')
        val outFile = outputDir.get().asFile.resolve("$pkgDir/NativeExports.kt")
        outFile.parentFile.mkdirs()

        val content = buildString {
            appendLine("@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)")
            appendLine()
            appendLine("package $pkg")
            appendLine()
            appendLine("import io.karandash.helpers.CAbiBridge")
            appendLine("import io.karandash.helpers.CAbiHost")
            appendLine("import io.karandash.helpers.CAbiHostCallFn")
            appendLine("import io.karandash.helpers.CAbiHostFreeFn")
            if (type == "RULE" || type == "PLATFORM_SERVICE") appendLine("import io.karandash.helpers.CAbiDataOps")
            if (type == "PLATFORM_SERVICE") {
                appendLine("import io.karandash.helpers.CAbiPlatformBridge")
                appendLine("import io.karandash.api.finding.MessageCode")
                appendLine("import io.karandash.api.finding.ModuleMessage")
            }
            appendLine("import kotlinx.cinterop.ByteVar")
            appendLine("import kotlinx.cinterop.COpaquePointer")
            appendLine("import kotlinx.cinterop.CPointer")
            appendLine("import kotlinx.cinterop.ExperimentalForeignApi")
            appendLine("import kotlin.experimental.ExperimentalNativeApi")
            appendLine("import kotlin.native.CName")
            appendLine()
            appendLine("private val module by lazy { $cls() }")
            appendLine()

            // Common functions present in ALL module types
            appendLine("""@CName("karandash_abi_version")""")
            appendLine("fun abiVersion(): Int = CAbiHost.ABI")
            appendLine()
            appendLine("""@CName("karandash_set_host")""")
            appendLine("fun setHost(")
            appendLine("    abi: Int,")
            appendLine("    hostCtx: COpaquePointer?,")
            appendLine("    call: CAbiHostCallFn?,")
            appendLine("    free: CAbiHostFreeFn?")
            appendLine("): Int = CAbiHost.set(abi, hostCtx, call, free)")
            appendLine()
            appendLine("""@CName("karandash_module_id")""")
            appendLine("fun moduleId(): CPointer<ByteVar> = CAbiBridge.toCString(module.id)")
            appendLine()
            appendLine("""@CName("karandash_module_name")""")
            appendLine("fun moduleName(): CPointer<ByteVar> = CAbiBridge.toCString(module.name)")
            appendLine()
            appendLine("""@CName("karandash_module_version")""")
            appendLine("fun moduleVersion(): CPointer<ByteVar> = CAbiBridge.toCString(module.version)")
            appendLine()
            appendLine("""@CName("karandash_module_type")""")
            appendLine("""fun moduleType(): CPointer<ByteVar> = CAbiBridge.toCString("$type")""")
            appendLine()
            appendLine("""@CName("karandash_describe")""")
            appendLine("fun describe(): CPointer<ByteVar> = CAbiBridge.describe(module)")
            appendLine()
            appendLine("""@CName("karandash_initialize")""")
            appendLine("fun initialize(ctxJson: CPointer<ByteVar>?): Int = CAbiBridge.call(0) { if (module.initialize(CAbiBridge.contextFromJson(ctxJson))) 1 else 0 }")
            appendLine()
            appendLine("""@CName("karandash_shutdown")""")
            appendLine("fun shutdown() = CAbiBridge.call(Unit) { module.shutdown() }")
            appendLine()

            // contributedRules - type-specific
            appendLine("""@CName("karandash_contributed_rules")""")
            if (type == "RULE") {
                appendLine("fun contributedRules(): CPointer<ByteVar> = CAbiBridge.serializeRules(module.contributedRules())")
            } else {
                appendLine("""fun contributedRules(): CPointer<ByteVar> = CAbiBridge.toCString("[]")""")
            }
            appendLine()

            // validate - type-specific
            if (type == "RULE") {
                appendLine("""@CName("karandash_validate")""")
                appendLine("fun validate(")
                appendLine("    r: CPointer<ByteVar>?,")
                appendLine("    m: CPointer<ByteVar>?,")
                appendLine("    c: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar> = CAbiBridge.wrapValidate(module, r, m, c)")
            } else {
                appendLine("""@Suppress("UnusedParameter")""")
                appendLine("""@CName("karandash_validate")""")
                appendLine("fun validate(")
                appendLine("    r: CPointer<ByteVar>?,")
                appendLine("    m: CPointer<ByteVar>?,")
                appendLine("    c: CPointer<ByteVar>?")
                appendLine("""): CPointer<ByteVar> = CAbiBridge.toCString("[]")""")
            }
            appendLine()

            // formatId - type-specific
            appendLine("""@CName("karandash_format_id")""")
            if (type == "REPORTER") {
                appendLine("fun formatId(): CPointer<ByteVar> = CAbiBridge.toCString(module.formatId)")
            } else {
                appendLine("""fun formatId(): CPointer<ByteVar> = CAbiBridge.toCString("")""")
            }
            appendLine()

            // report - type-specific
            if (type == "REPORTER") {
                appendLine("""@Suppress("UnusedParameter")""")
                appendLine("""@CName("karandash_report")""")
                appendLine("fun report(")
                appendLine("    f: CPointer<ByteVar>?,")
                appendLine("    p: Int,")
                appendLine("    c: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar>? = CAbiBridge.wrapReport(module, f, p, c)")
            } else {
                appendLine("""@Suppress("UnusedParameter")""")
                appendLine("""@CName("karandash_report")""")
                appendLine("fun report(")
                appendLine("    f: CPointer<ByteVar>?,")
                appendLine("    p: Int,")
                appendLine("    c: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar>? = null")
            }

            // Type-specific extra functions
            if (type == "PLATFORM_SERVICE") {
                appendLine()
                appendLine("""@CName("karandash_post_comment")""")
                appendLine("fun postComment(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    body: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar>? = CAbiPlatformBridge.wrapPostComment(module, projectId, mrId, body)")
                appendLine()
                appendLine("""@CName("karandash_update_comment")""")
                appendLine("fun updateComment(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    commentId: CPointer<ByteVar>?,")
                appendLine("    body: CPointer<ByteVar>?")
                appendLine("): Int = CAbiPlatformBridge.wrapUpdateComment(module, projectId, mrId, commentId, body)")
                appendLine()
                appendLine("""@CName("karandash_find_existing_comment")""")
                appendLine("fun findExistingComment(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    marker: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar>? = CAbiPlatformBridge.wrapFindExistingComment(module, projectId, mrId, marker)")
                appendLine()
                appendLine("""@CName("karandash_fetch_merge_request")""")
                appendLine("fun fetchMergeRequest(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar>? = CAbiPlatformBridge.wrapFetchMergeRequest(module, projectId, mrId)")
                appendLine()
                appendLine("""@CName("karandash_fetch_merge_request_by_url")""")
                appendLine("fun fetchMergeRequestByUrl(url: CPointer<ByteVar>?): CPointer<ByteVar>? = CAbiPlatformBridge.wrapFetchMergeRequestByUrl(module, url)")
                appendLine()
                appendLine("""@CName("karandash_default_priority")""")
                appendLine("fun defaultPriority(): Int = module.defaultPriority")
                appendLine()
                appendLine("""@CName("karandash_missing_auth_message")""")
                appendLine("fun missingAuthMessage(): CPointer<ByteVar>? {")
                appendLine("    val msg = CAbiBridge.call(emptyList()) { module.messages() }.firstOrNull { it.code == MessageCode.MISSING_AUTH }?.text ?: return null")
                appendLine("    return CAbiBridge.toCString(msg)")
                appendLine("}")
                appendLine()
                appendLine("""@CName("karandash_add_labels")""")
                appendLine("fun addLabels(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    labelsJson: CPointer<ByteVar>?")
                appendLine("): Int = CAbiPlatformBridge.wrapAddLabels(module, projectId, mrId, labelsJson)")
                appendLine()
                appendLine("""@CName("karandash_remove_labels")""")
                appendLine("fun removeLabels(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    labelsJson: CPointer<ByteVar>?")
                appendLine("): Int = CAbiPlatformBridge.wrapRemoveLabels(module, projectId, mrId, labelsJson)")
                appendLine()
                appendLine("""@CName("karandash_set_draft")""")
                appendLine("fun setDraft(")
                appendLine("    projectId: CPointer<ByteVar>?,")
                appendLine("    mrId: CPointer<ByteVar>?,")
                appendLine("    draft: Int")
                appendLine("): Int = CAbiPlatformBridge.wrapSetDraft(module, projectId, mrId, draft)")
            }

            // Data-only members through `CAbiDataOps`; validateFile / validateWithFindings stay hand-written,
            // since exporting them marks every rule file-granular / findings-aware
            val dataOps = if (type == "RULE") RULE_DATA_OPS else if (type == "PLATFORM_SERVICE") PLATFORM_DATA_OPS else emptyList()
            dataOps.forEach { op ->
                val args = listOf("module") + op.params.map { it.substringBefore(':') }
                appendLine()
                appendLine("""@CName("${op.cName}")""")
                appendLine("fun ${op.kotlinName}(${op.params.joinToString()}): ${op.returns} = CAbiDataOps.${op.kotlinName}(${args.joinToString()})")
            }

            if (type == "AI_ANALYZER") {
                appendLine()
                appendLine("""@CName("karandash_ai_analyzer_id")""")
                appendLine("fun aiAnalyzerId(): CPointer<ByteVar> = CAbiBridge.toCString(module.analyzerId)")
                appendLine()
                appendLine("""@CName("karandash_ai_feature_key")""")
                appendLine("fun aiFeatureKey(): CPointer<ByteVar> = CAbiBridge.toCString(module.featureKey)")
                appendLine()
                appendLine("""@CName("karandash_ai_default_prompt")""")
                appendLine("""fun aiDefaultPrompt(): CPointer<ByteVar> = CAbiBridge.toCString(module.defaultPrompt.orEmpty())""")
                appendLine()
                appendLine("""@CName("karandash_ai_enabled_by_default")""")
                appendLine("fun aiEnabledByDefault(): Int = if (module.defaultEnabled) 1 else 0")
                appendLine()
                appendLine("""@CName("karandash_ai_analyze")""")
                appendLine("fun aiAnalyze(")
                appendLine("    mr: CPointer<ByteVar>?,")
                appendLine("    budget: CPointer<ByteVar>?,")
                appendLine("    model: CPointer<ByteVar>?,")
                appendLine("    config: CPointer<ByteVar>?")
                appendLine("): CPointer<ByteVar> = CAbiBridge.wrapAnalyze(module, mr, budget, model, config)")
            }

            appendLine()
            appendLine("""@CName("karandash_free_string")""")
            appendLine("fun freeString(ptr: CPointer<ByteVar>?) = CAbiBridge.freeString(ptr)")
        }

        outFile.writeText(content)
    }

    private companion object {
        private const val STR = "CPointer<ByteVar>?"

        /** C symbol, Kotlin name (also the `CAbiDataOps` member), parameters, return type. */
        private class DataOp(val cName: String, val kotlinName: String, val params: List<String>, val returns: String)

        private fun op(cName: String, kotlinName: String, vararg params: String, returns: String = STR) =
            DataOp(cName, kotlinName, params.map { if (':' in it) it else "$it: $STR" }, returns)

        val RULE_DATA_OPS =
            listOf(
                op("karandash_usage", "usage"),
                op("karandash_required_merge_request_fields", "requiredMergeRequestFields", "ruleId", "configJson"),
                op("karandash_tokens_consumed", "tokensConsumed"),
                op("karandash_log_cost_summary", "logCostSummary"),
            )

        val PLATFORM_DATA_OPS =
            listOf(
                op("karandash_check_auth", "checkAuth"),
                op("karandash_fetch_file_blame", "fetchFileBlame", "projectId", "filePath", "ref"),
                op("karandash_fetch_file_content", "fetchFileContent", "projectId", "ref", "path"),
                op("karandash_supports_file_content", "supportsFileContent", returns = "Int"),
                op("karandash_search_merge_requests", "searchMergeRequests", "projectId", "queryJson", "limit: Int"),
                op("karandash_is_project_member", "isProjectMember", "projectId", "username"),
                op("karandash_current_username", "currentUsername"),
                op("karandash_fetch_approval_signals", "fetchApprovalSignals", "projectId", "mrId"),
                op("karandash_rate_limit_status", "rateLimitStatus"),
                op("karandash_permission_scope", "permissionScope"),
                op("karandash_token_location_hint", "tokenLocationHint"),
                op("karandash_not_found_guidance", "notFoundGuidance"),
                op("karandash_unavailable_merge_request_fields", "unavailableMergeRequestFields"),
                op("karandash_detect_confidence", "detectConfidence", "envJson"),
                op("karandash_supports", "supports", "operation", returns = "Int"),
                op(
                    "karandash_resolve_marked_inline_comments_for_files",
                    "resolveMarkedInlineCommentsForFiles",
                    "projectId",
                    "mrId",
                    "marker",
                    "filePathsJson",
                    returns = "Int",
                ),
                op("karandash_list_review_threads", "listReviewThreads", "projectId", "mrId"),
                op("karandash_add_reviewers", "addReviewers", "projectId", "mrId", "usernamesJson"),
                op("karandash_remove_reviewers", "removeReviewers", "projectId", "mrId", "usernamesJson"),
                op("karandash_merge_mr", "mergeMr", "projectId", "mrId", "optionsJson"),
                op("karandash_close_mr", "closeMr", "projectId", "mrId"),
                op("karandash_reopen_mr", "reopenMr", "projectId", "mrId"),
                op("karandash_resolve_thread", "resolveThread", "projectId", "mrId", "threadId", "note"),
                op("karandash_reply_to_thread", "replyToThread", "projectId", "mrId", "threadId", "body"),
                op("karandash_update_note", "updateNote", "projectId", "mrId", "threadId", "noteId", "body"),
            )
    }
}
