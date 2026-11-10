package io.karandash.cli.commands

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.profile.ProfileCommand
import io.karandash.cli.commands.profile.ProfileExportCommand
import io.karandash.cli.commands.profile.ProfileListCommand
import io.karandash.cli.commands.profile.ProfileShowCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `profile list/show/export` against the built-in profiles; an exported profile is a valid config. */
class ProfileCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-profile")
    private val bootstrap = PluginBootstrap(envReader = { null }, onFormatRegistryCreated = ::registerBuiltInRenderers)

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private suspend fun profile(vararg args: String) =
        bootstrap.loadPluginsFromDirs(emptyList()).let {
            ProfileCommand()
                .subcommands(ProfileListCommand(bootstrap), ProfileShowCommand(bootstrap), ProfileExportCommand(bootstrap))
                .test(args.toList())
        }

    @Test
    fun `list shows built-in profiles and filters by tag`() =
        runTest {
            val all = profile("list")
            assertEquals(ExitCodes.SUCCESS, all.statusCode, all.output)
            assertContains(all.stdout, "minimal")
            assertContains(all.stdout, "gitflow")

            val tagged = profile("list", "--tag", "draft-prevention")
            assertContains(tagged.stdout, "minimal")
            assertFalse("gitflow" in tagged.stdout, tagged.stdout)

            assertContains(profile("list", "--tag", "no-such-tag").stdout, "No profiles found with tag: no-such-tag")
        }

    @Test
    fun `show prints the profile rules and unknown id is a config error`() =
        runTest {
            val shown = profile("show", "minimal")
            assertEquals(ExitCodes.SUCCESS, shown.statusCode, shown.output)
            assertContains(shown.stdout, "Profile:  minimal")
            assertContains(shown.stdout, "titleFormat")

            val unknown = profile("show", "nope")
            assertEquals(ExitCodes.CONFIG_ERROR, unknown.statusCode)
            assertContains(unknown.stderr, "Unknown profile 'nope'")
        }

    /** The profile's declared run severity is not printed as a rule parameter. */
    @Test
    fun `show prints a declared severity apart from the rule parameters`() =
        runTest {
            val shown = profile("show", "minimal").stdout

            assertFalse("@severity" in shown, shown)
            assertTrue(Regex("""(?m)^ {2}fileChanges {2}.*\bseverity: """).containsMatchIn(shown), shown)
        }

    @Test
    fun `export in every format to a file passes config validate`() =
        runTest {
            for (format in listOf("yaml", "json", "kts")) {
                val out = Path(tempDir, format, "config.$format").toString()
                val exported = profile("export", "minimal", "--format", format, "--output", out)
                assertEquals(ExitCodes.SUCCESS, exported.statusCode, exported.output)
                assertContains(exported.stdout, "Profile 'minimal' exported to: $out")

                val validated = ConfigValidateCommand(bootstrap).test(listOf("--file", out))
                assertEquals(ExitCodes.SUCCESS, validated.statusCode, "$format: ${validated.output}")
            }
        }

    @Test
    fun `export json to stdout is parseable and unknown id is a config error`() =
        runTest {
            val exported = profile("export", "minimal", "--format", "json")
            assertEquals(ExitCodes.SUCCESS, exported.statusCode, exported.output)
            assertTrue("rules" in Json.parseToJsonElement(exported.stdout).jsonObject, exported.stdout)

            assertEquals(ExitCodes.CONFIG_ERROR, profile("export", "nope").statusCode)
        }
}
