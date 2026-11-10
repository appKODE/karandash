package io.karandash.cli.commands.mr

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.MergeOptions
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.commands.initPlatformService
import io.karandash.cli.commands.validate.PrUrlParser
import io.karandash.cli.commands.validate.withHint
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime

/**
 * Top-level `mr` command: merge, close or reopen an MR/PR by URL.
 *
 * ```
 * karandash mr merge https://gitlab.com/group/repo/-/merge_requests/7 --squash
 * karandash mr close https://github.com/owner/repo/pull/7
 * ```
 */
class MrCommand : CliktCommand(name = "mr") {
    override fun help(context: Context) = "Merge, close or reopen a merge/pull request"

    override fun run() = Unit
}

/**
 * Shared flow of the `mr` subcommands: parse the URL, initialize the platform service with its
 * `platforms:` config block (tokens come from the environment when it has none), run [apply],
 * shut the service down.
 */
abstract class MrActionCommand(
    name: String,
    bootstrap: PluginBootstrap,
    private val done: String,
) : CliCommandBase(name, bootstrap) {
    private val url by argument("url", help = "Merge/pull request URL")
    private val configOptions by ConfigFileOptions()
    private val configPath get() = configOptions.path

    protected abstract suspend fun apply(
        service: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): Result<Unit>

    override suspend fun execute(): Int {
        val registry = bootstrap.registry()
        val parsed = PrUrlParser(registry.platformServices()).parse(url, warnOnInsecureHttp = true)
        if (parsed == null) {
            echo("[ERROR] Cannot parse MR/PR URL: $url", err = true)
            return ExitCodes.CONFIG_ERROR
        }
        return try {
            val service = ProviderRuntime(ProviderResolver()).resolveModule(parsed.platform, registry)
            perform(service, parsed.projectId, parsed.mrId)
        } catch (e: ConfigException) {
            echo(withHint("[ERROR] ${e.message}", e.hint), err = true)
            ExitCodes.CONFIG_ERROR
        } catch (e: ProviderException) {
            echo("[ERROR] ${e.message}", err = true)
            ExitCodes.PROVIDER_ERROR
        }
    }

    private suspend fun perform(
        service: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): Int {
        val config = ConfigLoader.forCommand(configPath, bootstrap, logger).loadConfig().config
        bootstrap.initPlatformService(service, config)
        val result = try {
            apply(service, projectId, mrId)
        } finally {
            service.shutdown()
        }
        return result.fold(
            onSuccess = {
                echo("$done $url")
                ExitCodes.SUCCESS
            },
            onFailure = {
                echo("[ERROR] ${service.id}: ${it.message ?: it::class.simpleName}", err = true)
                ExitCodes.PROVIDER_ERROR
            },
        )
    }
}

/** `karandash mr merge <url>`: merges via [PlatformServiceModule.mergeMr]. */
class MrMergeCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : MrActionCommand("merge", bootstrap, "Merged") {
    override fun help(context: Context) = "Merge a merge/pull request"

    private val squash by option("--squash", help = "Squash the commits").flag()
    private val deleteSourceBranch by option("--delete-source-branch", help = "Delete the source branch after merging").flag()
    private val message by option("--message", "-m", help = "Merge commit message")

    override suspend fun apply(
        service: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): Result<Unit> = service.mergeMr(projectId, mrId, MergeOptions(squash, deleteSourceBranch, message))
}

/** `karandash mr close <url>`: closes (declines, abandons) via [PlatformServiceModule.closeMr]. */
class MrCloseCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : MrActionCommand("close", bootstrap, "Closed") {
    override fun help(context: Context) = "Close a merge/pull request without merging"

    override suspend fun apply(
        service: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): Result<Unit> = service.closeMr(projectId, mrId)
}

/** `karandash mr reopen <url>`: reopens via [PlatformServiceModule.reopenMr]. */
class MrReopenCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : MrActionCommand("reopen", bootstrap, "Reopened") {
    override fun help(context: Context) = "Reopen a closed merge/pull request"

    override suspend fun apply(
        service: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): Result<Unit> = service.reopenMr(projectId, mrId)
}
