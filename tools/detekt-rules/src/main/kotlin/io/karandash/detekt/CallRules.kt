package io.karandash.detekt

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression

/** `runCatching` catches `CancellationException` too: use `suspendCatching` (core/api) or catch specific types. */
class ForbiddenRunCatching(
    config: Config,
) : ForbiddenCallRule(config, "runCatching", "runCatching: use suspendCatching or catch specific types.")

/** `withTimeout` throws a `CancellationException` subtype; only the CLI entry may use it (`excludes` in detekt.yml). */
class ForbiddenWithTimeout(
    config: Config,
) : ForbiddenCallRule(config, "withTimeout", "withTimeout outside KarandashCli.kt: use withTimeoutOrNull and handle null.")

/** Ktor clients come from `ProviderHttpClientFactory` only (docs/development/conventions.md "HTTP"). */
class DirectHttpClient(
    config: Config,
) : ForbiddenCallRule(config, "HttpClient", "Direct HttpClient(: use ProviderHttpClientFactory.")

/** Env vars are read through `PlatformEnvironment.getEnv()` only (docs/development/conventions.md "Security Conventions"). */
class DirectGetenv(
    config: Config,
) : ForbiddenCallRule(config, "getenv", "Direct getenv: use PlatformEnvironment.getEnv.")

/**
 * Json instances come from `KarandashJson.Default` / `Strict` (`excludes` KarandashJson.kt in detekt.yml); a C-ABI
 * plugin that links no karandash library marks its own builder with `// standalone-plugin: <reason>` on or above it.
 */
class InlineJsonBuilder(
    config: Config,
) : ForbiddenCallRule(config, "Json", "Inline Json builder: use KarandashJson.Default / Strict.") {
    override fun violation(call: KtCallExpression): String? =
        description.takeUnless { call.hasMarkerComment("standalone-plugin:", before = 1, after = 0) }
}

/** `CoroutineScope(EmptyCoroutineContext)` is a scope nobody owns or cancels. */
class UnownedCoroutineScope(
    config: Config,
) : ForbiddenCallRule(config, "CoroutineScope", "CoroutineScope(EmptyCoroutineContext): give the scope an owned Job.") {
    override fun violation(call: KtCallExpression): String? =
        description.takeIf {
            call.valueArguments
                .singleOrNull()
                ?.getArgumentExpression()
                ?.text
                ?.endsWith("EmptyCoroutineContext") == true
        }
}

/**
 * `runBlocking` only at the bridge boundaries listed in `sanctionedFiles` (path suffixes), each with a
 * `// bridge-boundary: <reason>` comment in the 3 lines above it or as the first line of its block
 * (docs/development/conventions.md "Sanctioned runBlocking Sites").
 */
class UnsanctionedRunBlocking(
    config: Config,
) : ForbiddenCallRule(config, "runBlocking", "runBlocking outside the sanctioned bridge files (docs/development/conventions.md).") {
    private val sanctionedFiles: List<String> = config.valueOrDefault("sanctionedFiles", emptyList())

    override fun violation(call: KtCallExpression): String? {
        val path = call.filePath
        return when {
            sanctionedFiles.none { path == it || path.endsWith("/$it") } -> description
            call.hasMarkerComment("bridge-boundary:", before = 3, after = 1) -> null
            else -> "runBlocking without // bridge-boundary: <reason>."
        }
    }
}
