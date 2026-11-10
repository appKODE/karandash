package io.karandash.helpers.guest

import io.karandash.api.Module
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.nullIfUnsupported
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A guest op's string arguments in `BridgeAbi` order; a missing one reads as `""`. */
class GuestArgs(
    val values: List<String>,
) {
    operator fun get(index: Int): String = values.getOrElse(index) { "" }
}

/** One `BridgeAbi` op a guest serves for every module that [accepts]; [run] answers the op's host-side encoding. */
class GuestOp(
    val accepts: (Module) -> Boolean,
    val run: suspend (Module, GuestArgs) -> String?,
)

/** An op served for every module implementing [T]. */
inline fun <reified T : Any> guestOp(noinline run: suspend T.(GuestArgs) -> String?): GuestOp =
    GuestOp({ it is T }) { module, args -> (module as T).run(args) }

/** The encodings every guest runtime shares, matching the host's `KarandashJson.Default` reads. */
object GuestCodec {
    val json = KarandashJson.Default
    private val findingsSerializer = ListSerializer(Finding.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    fun mr(value: String): MergeRequest = json.decodeFromString(MergeRequest.serializer(), value)

    fun element(value: String): JsonElement = if (value.isBlank()) JsonNull else json.parseToJsonElement(value)

    /** A config argument the host sends as `"null"` when absent. */
    fun optionalElement(value: String): JsonElement? = element(value).takeUnless { it is JsonNull }

    fun findings(value: String): List<Finding> = json.decodeFromString(findingsSerializer, value)

    fun encodeFindings(findings: List<Finding>): String = json.encodeToString(findingsSerializer, findings)

    fun strings(value: String): List<String> = json.decodeFromString(stringsSerializer, value)

    fun encodeStrings(values: Collection<String>): String = json.encodeToString(stringsSerializer, values.toList())

    /** A `#` (raw int) argument; a BOOL answer `"true"` reads as 1. */
    fun intArg(value: String): Int = value.toIntOrNull() ?: if (value == "true") 1 else 0

    /** The host sends labels as a JSON list; the comma-joined form older hosts sent is still read. */
    fun labels(value: String): List<String> =
        if (value.trimStart().startsWith("[")) strings(value) else value.split(',').filter { it.isNotEmpty() }
}

/**
 * The guest side of the `BridgeAbi` ops every guest runtime serves the same way: rule validation, reporting and the
 * [PlatformServiceModule] fetch, mutator and comment ops. The JAR bridge server and the Kotlin/Native C ABI exports
 * both answer from this table, so the MR/findings decoding and the mutator answers cannot drift between them.
 * BOOL mutators answer `"true"`; a failure reaches the caller as an exception.
 */
object GuestOpTable {
    private val json = GuestCodec.json

    private val rule: Map<String, GuestOp> =
        mapOf(
            "validate" to guestOp<RuleModule> {
                GuestCodec.encodeFindings(validate(it[0], GuestCodec.mr(it[1]), GuestCodec.element(it[2])))
            },
            "report" to guestOp<ReporterModule> {
                report(GuestCodec.findings(it[0]), GuestCodec.intArg(it[1]) == 1, GuestCodec.element(it[2]))
            },
        )

    private val fetch: Map<String, GuestOp> =
        mapOf(
            "fetchMergeRequest" to platformOp { json.encodeToString(fetchMergeRequest(it[0], it[1])) },
            "fetchMergeRequestByUrl" to platformOp { json.encodeToString(fetchMergeRequestByUrl(it[0])) },
            "parseMergeRequestUrl" to platformOp { args ->
                parseMergeRequestUrl(args[0])?.let { json.encodeToString(ParsedMergeRequestUrl.serializer(), it) }
            },
            "parseGitRemoteUrl" to platformOp { parseGitRemoteUrl(it[0]) },
            "defaultApiUrl" to platformOp { defaultApiUrl().orEmpty() },
            "authTokenHint" to platformOp { authTokenHint().orEmpty() },
            "aliases" to platformOp { GuestCodec.encodeStrings(aliases()) },
        )

    private val mutators: Map<String, GuestOp> =
        mapOf(
            "addLabels" to mutatorOp { addLabels(it[0], it[1], GuestCodec.labels(it[2])) },
            "removeLabels" to mutatorOp { removeLabels(it[0], it[1], GuestCodec.labels(it[2])) },
            "setDraft" to mutatorOp { setDraft(it[0], it[1], GuestCodec.intArg(it[2]) == 1) },
            "updateTitle" to mutatorOp { updateTitle(it[0], it[1], it[2]) },
            "updateDescription" to mutatorOp { updateDescription(it[0], it[1], it[2]) },
            "approve" to mutatorOp { approve(it[0], it[1]) },
            "unapprove" to mutatorOp { unapprove(it[0], it[1]) },
            "updateComment" to mutatorOp { updateComment(it[0], it[1], it[2], it[3]) },
            "deleteComment" to mutatorOp { deleteComment(it[0], it[1], it[2]) },
        )

    private val comments: Map<String, GuestOp> =
        mapOf(
            "postComment" to platformOp { postComment(it[0], it[1], it[2]) },
            "findExistingComment" to platformOp { findExistingComment(it[0], it[1], it[2]) },
            "postInlineComment" to platformOp { args ->
                val diffRefs = json.decodeFromString(DiffRefs.serializer(), args[5])
                // #start_line 0 (or absent) = single line
                val startLine = GuestCodec.intArg(args[6]).takeIf { it != 0 }
                postInlineComment(args[0], args[1], args[2], args[3], GuestCodec.intArg(args[4]), diffRefs, startLine)
            },
            "renderSuggestionBlock" to platformOp { renderSuggestionBlock(it[0]) },
            "deleteMarkedInlineComments" to platformOp { deleteMarkedInlineComments(it[0], it[1], it[2]).toString() },
            "resolveMarkedInlineComments" to platformOp { resolveMarkedInlineComments(it[0], it[1], it[2]).toString() },
            "findAllMatchingComments" to platformOp { GuestCodec.encodeStrings(findAllMatchingComments(it[0], it[1], it[2])) },
            "listOpenMergeRequestIds" to platformOp {
                nullIfUnsupported { listOpenMergeRequestIds(it[0], it[1], GuestCodec.intArg(it[2])) }?.let(GuestCodec::encodeStrings)
            },
            "listDismissedInlineComments" to platformOp {
                nullIfUnsupported { listDismissedInlineComments(it[0], it[1], it[2]) }?.let(GuestCodec::encodeStrings)
            },
        )

    val OPS: Map<String, GuestOp> = rule + fetch + mutators + comments

    /** Runs [op] on [module]; `null` is the op's "no answer". Fails when [module] does not implement the op's interface. */
    suspend fun run(
        module: Module,
        op: String,
        vararg args: String,
    ): String? {
        val handler = OPS[op]
        require(handler != null && handler.accepts(module)) { "Module '${module.id}' does not answer op '$op'" }
        return handler.run(module, GuestArgs(args.toList()))
    }

    private fun platformOp(run: suspend PlatformServiceModule.(GuestArgs) -> String?): GuestOp = guestOp(run)

    private fun mutatorOp(run: suspend PlatformServiceModule.(GuestArgs) -> Unit): GuestOp =
        guestOp<PlatformServiceModule> {
            run(it)
            true.toString()
        }
}
