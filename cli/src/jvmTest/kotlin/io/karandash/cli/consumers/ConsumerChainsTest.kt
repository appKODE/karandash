package io.karandash.cli.consumers

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.manifest.ConfigSource
import io.karandash.patterns.CorePatternRegistry
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.PatternDefinition
import io.karandash.patterns.PatternResolver
import io.karandash.testfixtures.GoldenFiles
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The review-bot `base`@1.38.0/@1.41.0/@1.44.0 and `base-kmp`@1.41.0 configs and the
 * six app configs that extend them (app-bravo, app-foxtrot, app-charlie, app-golf, app-alpha, app-delta) are frozen under
 * `cli/src/jvmTest/resources/consumers/`. Remote `extends:` urls are served by MockEngine from those files.
 * Per app the chain must load without error, keep the same rule set and the same compiled patterns (golden), and
 * load under `--strict` from a local copy of the same layout.
 */
class ConsumerChainsTest {
    private val requested = mutableListOf<String>()

    private fun fetcher(): RemoteConfigFetcher =
        RemoteConfigFetcher(
            HttpClient(
                MockEngine { request ->
                    requested += request.url.toString()
                    val file = request.url.encodedPath
                        .substringAfter("/files/")
                        .substringBefore("/raw")
                    val dir = if (file.startsWith("base-kmp%2F")) "base-kmp" else "base"
                    val path = Path(ROOT, "review-bot", "$dir-${request.url.parameters["ref"]}", "config.yaml")
                    if (TestFiles.exists(path)) {
                        respond(
                            ByteReadChannel(TestFiles.readText(path)),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/yaml")
                        )
                    } else {
                        respond(ByteReadChannel("not found"), HttpStatusCode.NotFound)
                    }
                },
            ),
        )

    private suspend fun bootstrap(): PluginBootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }

    /** Rule ids plus every `patterns:` entry resolved to its compiled regex, one line each. */
    private suspend fun snapshot(app: String): String {
        val bootstrap = bootstrap()
        try {
            val formatRegistry = bootstrap.formatRegistry
            val fetcher = fetcher()
            val dir = TestFiles.createTestTempDirectory("consumer-chain-$app")
            TestFiles.writeText(Path(dir, "config.yaml"), TestFiles.readText(Path(ROOT, "apps", app, "config.yaml")))
            copyAppFiles(Path(dir))
            val chain = ConfigChainResolver(formatRegistry, fetcher = fetcher)
                .buildChain(ConfigSource(Path(dir, "config.yaml").toString()))
            val merged = ConfigChainParser(formatRegistry, fetcher = fetcher).parseAndMerge(chain)
            TestFiles.deleteRecursively(dir)
            fetcher.close()
            val config = ConfigMapParser.parseFullConfig(merged, ConfigMapParser.parseEnvBlock(merged).defaults).config
            val registry = CorePatternRegistry.create()
            bootstrap.registry().registeredIds().forEach { bootstrap.registry().get(it)?.registerPatterns(registry) }
            config.patterns.forEach { (name, text) ->
                registry.register(PatternDefinition(name = name, description = "user", params = emptyList(), expand = { text }))
            }
            val resolver = PatternResolver(registry)
            val patterns = config.patterns.keys.sorted().map { name ->
                when (val result = resolver.resolve("@$name")) {
                    is PatternCompilationResult.Success -> "$name = ${result.regex.pattern}"
                    is PatternCompilationResult.Error -> "$name = ERROR ${result.message}"
                }
            }
            return (
                listOf(
                    "rules: " + config.rules.ruleConfigs.keys
                        .sorted()
                        .joinToString(" ")
                ) + patterns
            ).joinToString("\n")
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `consumer chains load with the same rules and compiled patterns`() =
        runTest(timeout = 5.minutes) {
            val mismatches = APPS.map { app ->
                val text = snapshot(app)
                assertTrue("ERROR" !in text, "$app: $text")
                GoldenFiles.check(Path(ROOT, "golden"), "$app.txt", text)
            }
            GoldenFiles.assertAllMatch(mismatches.filterNotNull())
            assertTrue(requested.isNotEmpty() && requested.all { "projects/1234/repository/files" in it }, "$requested")
        }

    /** The remote layers each app asked for: base at the pinned ref, base-kmp for app-alpha. */
    @Test
    fun `apps request the pinned review bot refs`() =
        runTest(timeout = 5.minutes) {
            APPS.forEach { snapshot(it) }
            assertTrue(requested.any { "base-kmp%2F" in it && "ref=1.41.0" in it }, "app-alpha pins base-kmp 1.41.0: $requested")
            assertTrue(requested.any { "base%2F" in it && "ref=1.38.0" in it }, "app-delta pins 1.38.0: $requested")
            assertTrue(requested.any { "base%2F" in it && "ref=1.41.0" in it }, "app-bravo pins 1.41.0: $requested")
            assertTrue(requested.any { "base%2F" in it && "ref=1.44.0" in it }, "app-echo pins 1.44.0: $requested")
        }

    /** The prompt and scope files every app keeps next to its config; stubs, the guard only needs them to exist. */
    private fun copyAppFiles(into: Path) {
        APP_FILES.forEach { TestFiles.writeText(Path(into, it), TestFiles.readText(Path(ROOT, "app-files", it))) }
    }

    /** Same layout as the review-bot repo (`base/.karandash`, `base-kmp/.karandash`, `<app>/.karandash`), local only. */
    private fun stage(app: String): Path {
        val dir = TestFiles.createTestTempDirectory("consumer-$app")
        val lines = TestFiles.readText(Path(ROOT, "apps", app, "config.yaml")).lines()
        val baseVersion = checkNotNull(PINNED_REF.find(lines.joinToString("\n"))) { "$app pins no ref" }.groupValues[1]
        TestFiles.writeText(
            Path(dir, "base", ".karandash", "config.yaml"),
            TestFiles.readText(Path(ROOT, "review-bot", "base-$baseVersion", "config.yaml"))
        )
        TestFiles.writeText(
            Path(dir, "base-kmp", ".karandash", "config.yaml"),
            TestFiles.readText(Path(ROOT, "review-bot", "base-kmp-1.41.0", "config.yaml")),
        )
        // base and base-kmp `{ file: }` refs resolve next to the base config: give them the same files the app keeps
        copyAppFiles(Path(dir, "base", ".karandash"))
        copyAppFiles(Path(dir, "base-kmp", ".karandash"))
        val parent = if (app == "app-alpha") "base-kmp" else "base"
        val kept = mutableListOf<String>()
        var skipAuth = false
        for (line in lines) {
            if (line == "  auth:") {
                skipAuth = true
            } else if (skipAuth && line.startsWith("    ")) {
                continue
            } else {
                skipAuth = false
                kept += if (line.trimStart().startsWith("url:")) "  url: \"../../$parent/.karandash/config.yaml\"" else line
            }
        }
        TestFiles.writeText(Path(dir, app, ".karandash", "config.yaml"), kept.joinToString("\n") + "\n")
        copyAppFiles(Path(dir, app, ".karandash"))
        return dir
    }

    /**
     * `config validate --strict` on the staged layout. Strict fails on unresolved env vars, and the test process has none of the
     * consumer secrets, so the only acceptable failure is exactly that line; any other failure, or a missing `{ file: }`, is a break.
     */
    @Test
    fun `consumer chains pass config validate strict`() =
        runTest(timeout = 5.minutes) {
            val mismatches = APPS.map { app ->
                val dir = stage(app)
                val bootstrap = bootstrap()
                val result = try {
                    ConfigValidateCommand(
                        bootstrap
                    ).test(listOf("--strict", "--file", Path(dir, app, ".karandash", "config.yaml").toString()))
                } finally {
                    bootstrap.shutdown()
                    TestFiles.deleteRecursively(dir)
                }
                val text = result.output.replace(dir.toString(), "<tmp>")
                assertTrue("not found" !in text, "$app: $text")
                val failures = text.lines().filter { "validation failed" in it }
                assertTrue(
                    result.statusCode == 0 ||
                        (result.statusCode == ExitCodes.CONFIG_ERROR && failures.all { "unresolved environment variable(s)" in it }),
                    "$app exit ${result.statusCode}: $text",
                )
                // key-notice lines are owned by the config key tests; the guard pins only loader warnings and failures
                GoldenFiles.check(
                    Path(ROOT, "golden"),
                    "$app.strict-output.txt",
                    text.lines().filterNot { it.contains("Config key '") }.joinToString("\n"),
                )
            }
            GoldenFiles.assertAllMatch(mismatches.filterNotNull())
        }

    private companion object {
        const val ROOT = "cli/src/jvmTest/resources/consumers"
        val APPS = listOf("app-alpha", "app-bravo", "app-charlie", "app-delta", "app-echo", "app-foxtrot", "app-golf")
        val PINNED_REF = Regex("""ref=([0-9.]+)""")
        val APP_FILES = listOf(
            "instructions/extra-instructions.md",
            "prompts/review-prompt.md",
            "scopes/android-instructions.md",
            "scopes/android-best-practices.md",
            "scopes/ci-instructions.md",
            "scopes/database-instructions.md",
            "scopes/ios-instructions.md",
            "scopes/ios-best-practices.md",
            "scopes/core-instructions.md",
            "scopes/core-best-practices.md",
            "scopes/shared-instructions.md",
            "scopes/shared-best-practices.md",
        )
    }
}
