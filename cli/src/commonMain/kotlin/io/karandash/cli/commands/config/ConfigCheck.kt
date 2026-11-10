package io.karandash.cli.commands.config

import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.withHint
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.ConfigPatterns
import io.karandash.patterns.PatternResolver
import io.karandash.patterns.PomskyCompilerFactory
import io.karandash.patterns.PomskyConfigCheck
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * The checks of `karandash config validate` as a library, shared by that command and the language server: loads
 * the config exactly as `validate` does (extends chain, `{ file: }` refs, `${VAR}` interpolation, module schemas)
 * and compiles every `pomsky:` pattern. Checking stops at the first group of errors, as the command exits there.
 */
class ConfigCheck(
    private val bootstrap: PluginBootstrap,
) {
    /** Severity of a [Message]: an error fails `config validate`, a warning does not. */
    @Serializable
    enum class Severity { ERROR, WARNING }

    /** One finding; [keyPath] is the config key it is about, outermost first, empty when it is about the whole file. */
    @Serializable
    data class Message(
        val severity: Severity,
        val keyPath: List<String>,
        val text: String,
    )

    /** The [messages] of a check, and the number of configured rules when the config loaded. */
    @Serializable
    data class Result(
        val messages: List<Message>,
        val ruleCount: Int?,
    ) {
        val failed: Boolean get() = messages.any { it.severity == Severity.ERROR }
    }

    /**
     * Checks [configFile]. [strict] makes unresolved variables and schema mismatches errors; [base] lets a missing
     * `{ file: }` reference warn. [readFile] reads [configFile] itself (the editor buffer in the language server).
     */
    @Suppress("TooGenericExceptionCaught") // boundary: config load errors from multiple sources
    suspend fun check(
        configFile: Path,
        strict: Boolean = false,
        base: Boolean = false,
        readFile: ((Path) -> String)? = null,
    ): Result {
        val messages = mutableListOf<Message>()
        if (bootstrap.formatRegistry.getModuleForFile(configFile.toString()) == null) {
            return Result(listOf(error("Unsupported config format: $configFile")), null)
        }
        val stage = try {
            ConfigLoader
                .forCommand(
                    configFile.toString(),
                    bootstrap,
                    collecting(messages),
                    missingFilesWarn = base,
                    strict = strict,
                    readFile = readFile
                ).loadConfig()
        } catch (e: Exception) {
            // boundary: config parse errors from multiple sources
            e.rethrowCancellation()
            return Result(messages + error(e.message.orEmpty()), null)
        }
        messages += afterLoad(stage, strict)
        return Result(messages, if (messages.any { it.severity == Severity.ERROR }) null else stage.config.rules.ruleConfigs.size)
    }

    private fun afterLoad(
        stage: ConfigLoadStage,
        strict: Boolean,
    ): List<Message> {
        if (stage.missingEnvVars.isNotEmpty()) {
            val names = stage.missingEnvVars.sorted().joinToString(", ")
            if (strict) return listOf(error("unresolved environment variable(s): $names"))
        }
        val envWarning = stage.missingEnvVars.takeIf { it.isNotEmpty() }?.let {
            Message(
                Severity.WARNING,
                emptyList(),
                "unresolved environment variable(s): ${it.sorted().joinToString(", ")} (--strict fails on these)"
            )
        }
        val pomsky = stage.rawElement?.let(PomskyConfigCheck::check).orEmpty().sortedBy { it.error }.map {
            Message(if (it.error) Severity.ERROR else Severity.WARNING, it.path.split('.'), withHint(it.message, it.hint))
        }
        if (pomsky.any { it.severity == Severity.ERROR }) return listOfNotNull(envWarning) + pomsky
        val patterns = stage.config.patterns
        val resolver = PatternResolver(ConfigPatterns.registry(patterns, stage.registry), PomskyCompilerFactory.create())
        return listOfNotNull(envWarning) + pomsky + ConfigPatterns.errors(patterns, resolver).map(::error)
    }

    /** Config-loading warnings are findings here; a `[SCHEMA] <module>: ...` warning is about that module's key. */
    private fun collecting(messages: MutableList<Message>): Logger =
        object : Logger by bootstrap.logger {
            override fun warn(message: String) {
                val module = message.removePrefix(SCHEMA_PREFIX).takeIf { it != message }?.substringBefore(':')
                messages += Message(Severity.WARNING, listOfNotNull(module), message)
            }
        }

    private fun error(text: String) = Message(Severity.ERROR, emptyList(), text)

    private companion object {
        const val SCHEMA_PREFIX = "[SCHEMA] "
    }
}
