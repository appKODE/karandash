@file:OptIn(ExperimentalKarandashApi::class)

package io.karandash.loader.jar

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.config.ConfigProperty
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.support.suspendCatching
import io.karandash.helpers.guest.GuestCodec
import io.karandash.helpers.guest.GuestCodec.element
import io.karandash.helpers.guest.GuestCodec.encodeFindings
import io.karandash.helpers.guest.GuestCodec.encodeStrings
import io.karandash.helpers.guest.GuestCodec.findings
import io.karandash.helpers.guest.GuestCodec.json
import io.karandash.helpers.guest.GuestCodec.mr
import io.karandash.helpers.guest.GuestCodec.optionalElement
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.BridgeCliResult
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** JAR bridge ops of the module core and every non-platform, non-AI type. */
internal object JarModuleOps {
    private val core: Map<String, JarBridgeOp> =
        mapOf(
            "minApiLevel" to jarOp<Module> { minApiLevel.toString() },
            "isDefaultEnabled" to jarOp<Module> { defaultEnabled.toString() },
            "defaultConfig" to jarOp<Module> { defaultConfig().toString() },
            "configSchema" to jarOp<Module> { encodeSchema(configSchema()) },
            "messages" to jarOp<Module> { json.encodeToString(ListSerializer(ModuleMessage.serializer()), messages()) },
        )

    private val rule: Map<String, JarBridgeOp> =
        mapOf(
            "contributedRules" to jarOp<RuleModule> { encodeStrings(contributedRules()) },
            "ruleExplanation" to jarOp<RuleModule> { ruleExplanation(it[0]) },
        )

    private val reporter: Map<String, JarBridgeOp> =
        mapOf(
            "formatId" to JarBridgeOp({ it is ReporterModule || it is FormatModule }) { module, _ ->
                (module as? ReporterModule)?.formatId ?: (module as? FormatModule)?.formatId
            },
            "reportEnriched" to jarOp<EnrichedReporterModule> { reportEnriched(validationReport(it[0]), element(it[1])) },
            "runtimeReporterConfig" to jarOp<ReporterModule> { runtimeReporterConfig(element(it[0]), element(it[1])).toString() },
        )

    private val profile: Map<String, JarBridgeOp> =
        mapOf(
            "profileId" to jarOp<ProfileModule> { profileId },
            "summary" to jarOp<ProfileModule> { summary },
            "tags" to jarOp<ProfileModule> { encodeStrings(tags()) },
            "ruleConfigs" to jarOp<ProfileModule> { JsonObject(ruleConfigs()).toString() },
            "reporterConfigs" to jarOp<ProfileModule> { JsonObject(reporterConfigs()).toString() },
            "defaultSeverity" to jarOp<ProfileModule> { defaultSeverity.name },
            "isDefaultZeroConfigProfile" to jarOp<ProfileModule> { isDefaultZeroConfigProfile.toString() },
        )

    private val content: Map<String, JarBridgeOp> =
        mapOf(
            "enrichmentIds" to jarOp<EnrichmentProviderModule> { encodeStrings(providedEnrichment) },
            "enrich" to jarOp<EnrichmentProviderModule> { JsonObject(enrich(mr(it[0]), element(it[1]))).toString() },
            "execute" to jarOp<ScriptModule> { call ->
                val scripts = (element(call[0]) as? JsonArray).orEmpty()
                encodeFindings(execute(scripts, mr(call[1]), call.contextOf(call[2].ifBlank { null })))
            },
            "format" to jarOp<FormatModule> {
                json.encodeToString(FormattedReport.serializer(), format(validationReport(it[0]), element(it[1])))
            },
            "outputId" to jarOp<OutputModule> { outputId },
            // NULL on success, else the error message
            "output" to jarOp<OutputModule> { call ->
                val report = json.decodeFromString(FormattedReport.serializer(), call[0])
                suspendCatching { output(report, element(call[1])) }.exceptionOrNull()?.let { it.message ?: "output failed" }
            },
        )

    private val pipeline: Map<String, JarBridgeOp> =
        mapOf(
            "onConfigured" to jarOp<PipelineExtension> { call ->
                val env = element(call[0]).jsonObject
                val text = { key: String -> (env[key] as? JsonPrimitive)?.contentOrNull }
                val rawConfig = env["rawConfig"]?.let { optionalElement(it.toString()) }
                // the host's AiService does not cross the bridge
                onConfigured(PipelineEnvironment(null, rawConfig, text("statsDir").orEmpty(), text("platformId")))
                null
            },
            "onPostFetch" to jarOp<PipelineExtension> {
                onPostFetch(mr(it[0]), optionalElement(it[1]))
                null
            },
            "afterValidation" to jarOp<PipelineExtension> {
                encodeFindings(afterValidation(findings(it[0]), mr(it[1]), optionalElement(it[2])))
            },
            "beforeReporting" to jarOp<PipelineExtension> { encodeFindings(beforeReporting(findings(it[0]), mr(it[1]))) },
            "onPipelineComplete" to jarOp<PipelineExtension> {
                onPipelineComplete()
                null
            },
            "invalidateRules" to jarOp<PipelineExtension> {
                invalidateRules(GuestCodec.strings(it[0]).toSet())
                null
            },
        )

    private val commands: Map<String, JarBridgeOp> =
        mapOf(
            "cliCommands" to jarOp<CliCommandModule> { encodeCommands(cliCommands()).toString() },
            "runCliCommand" to jarOp<CliCommandModule> { runCommand(it) },
            "configFormatId" to jarOp<ConfigFormatModule> { formatId },
            "configFormatExtensions" to jarOp<ConfigFormatModule> { encodeStrings(fileExtensions) },
            "configParse" to jarOp<ConfigFormatModule> { parseToJsonElement(it[0]).toString() },
            "configParseExtends" to jarOp<ConfigFormatModule> { parseExtendsOnly(it[0])?.toString() },
        )

    val OPS: Map<String, JarBridgeOp> = core + rule + reporter + profile + content + pipeline + commands

    /** The `BridgeDeserializer.parsePluginCommandSpecs` shape, subcommands included. */
    private fun encodeCommands(specs: List<PluginCommandSpec>): JsonArray =
        buildJsonArray {
            specs.forEach { spec ->
                add(
                    buildJsonObject {
                        put("name", spec.name)
                        put("help", spec.help)
                        put("subcommands", encodeCommands(spec.subcommands))
                    },
                )
            }
        }

    /** Runs the handler at the command path (`"example greet"`) with echo captured into the result the host prints. */
    private suspend fun CliCommandModule.runCommand(call: JarInvocation): String {
        val path = call[0].split(' ').filter { it.isNotEmpty() }
        val leaf = path.drop(1).fold(cliCommands().firstOrNull { it.name == path.firstOrNull() }) { spec, name ->
            spec?.subcommands?.firstOrNull { it.name == name }
        }
        val handler = requireNotNull(leaf?.run) { "Module '$id' has no runnable command '${call[0]}'" }
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val args = GuestCodec.strings(call[1].ifBlank { "[]" })
        val exitCode = handler(PluginCommandContext(args, call.contextOf(null), { stdout += it }, { stderr += it }))
        return json.encodeToString(BridgeCliResult.serializer(), BridgeCliResult(exitCode, stdout, stderr))
    }

    private fun validationReport(value: String): ValidationReport =
        KarandashJson.Report.decodeFromString(ValidationReport.serializer(), value)

    /** The `BridgeUtils.parseConfigProperty` shape; `defaultValue` is contextual, so it is written as its JSON twin. */
    private fun encodeSchema(schema: List<ConfigProperty>): String =
        buildJsonArray {
            schema.forEach { property ->
                add(
                    buildJsonObject {
                        put("name", property.name)
                        put("type", property.type.name)
                        put("description", property.description)
                        put("required", property.required)
                        property.defaultJson?.let { put("defaultValue", it) }
                        property.enumValues?.let { values -> put("enumValues", JsonArray(values.map(::JsonPrimitive))) }
                        property.deprecated?.let { put("deprecated", it) }
                    },
                )
            }
        }.toString()
}
