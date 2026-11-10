package io.karandash.cli.docs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `action.yml` is a working composite action: it downloads from GitHub Releases,
 * takes the token from an input, reads the finding count from `--format json` and exposes its results as outputs.
 * The behaviour of the scripts is exercised end to end by `tools/ci/selfcheck-action-yml.sh` (an `act`-style smoke run).
 */
class ActionYmlTest {
    private val action = DocsSupport.read("action.yml")
    private val validate = DocsSupport.readOrEmpty("tools/action/validate.sh")

    @Test
    fun `action downloads the jar from GitHub Releases and verifies its checksum`() {
        val install = action + DocsSupport.readOrEmpty("tools/action/install.sh")
        listOf("PRIVATE-TOKEN", "packages/generic").forEach {
            assertFalse(install.contains(it), "action or install.sh still references '$it'")
        }
        assertContains(install, "https://github.com/appKODE/karandash/releases/download")
        assertContains(install, "https://api.github.com/repos/appKODE/karandash")
        assertContains(install, "karandash.jar")
        assertContains(install, "SHA256SUMS")
    }

    @Test
    fun `action declares the token reviewdog-format and config inputs`() {
        val inputs = action.substringAfter("\ninputs:").substringBefore("\noutputs:")
        listOf("token", "reviewdog-format", "config", "sarif", "format", "version").forEach {
            assertTrue(Regex("(?m)^ {2}$it:").containsMatchIn(inputs), "input '$it' missing")
        }
    }

    @Test
    fun `token comes from an input and not from the caller env`() {
        assertContains(action, "inputs.token")
        assertFalse(action.contains("env.GITHUB_TOKEN"), "action.yml still reads GITHUB_TOKEN from env.")
    }

    @Test
    fun `findings count comes from json output not from grepping the console`() {
        assertContains(validate, "formats=(json)")
        assertContains(validate, "findings | length")
        assertFalse(validate.contains("grep -oE"), "findings are still counted by grepping console output")
    }

    @Test
    fun `sarif comes from format sarif and outputs are declared`() {
        assertContains(validate, "output: karandash-results.")
        val outputs = action.substringAfter("\noutputs:").substringBefore("\nruns:")
        listOf("passed", "findings-count", "sarif-file", "reviewdog-enabled").forEach {
            assertTrue(Regex("(?m)^ {2}$it:").containsMatchIn(outputs), "output '$it' missing")
        }
    }

    private val steps = action.substringAfter("\nruns:").split(Regex("(?m)^ {4}- name:")).drop(1)

    private fun scriptOf(step: String): String? = Regex("tools/action/(\\w+)\\.sh").find(step)?.groupValues?.get(1)

    @Test
    fun `every INPUT variable a script reads is set by its action step`() {
        val checked = steps.mapNotNull { step -> scriptOf(step)?.let { it to step } }
        assertTrue(checked.size >= 2, "action steps running tools/action scripts not found")
        checked.forEach { (script, step) ->
            val read = Regex("INPUT_[A-Z_]+").findAll(DocsSupport.read("tools/action/$script.sh")).map { it.value }.toSet()
            val set = Regex("(?m)^ +(INPUT_[A-Z_]+):").findAll(step).map { it.groupValues[1] }.toSet()
            assertTrue(read.all { it in set }, "$script.sh reads ${read - set} which action.yml does not set")
        }
    }

    @Test
    fun `every step output the action exposes is written by its script`() {
        Regex("steps\\.(\\w+)\\.outputs\\.([\\w-]+)").findAll(action).forEach { m ->
            val (id, name) = m.destructured
            val script = steps.first { it.contains("id: $id\n") }.let(::scriptOf)
            assertTrue(script != null, "step '$id' runs no tools/action script")
            assertTrue(
                Regex(
                    "echo \"${Regex.escape(name)}=.*>> \"?\\$(OUT|\\{?GITHUB_OUTPUT)"
                ).containsMatchIn(DocsSupport.read("tools/action/$script.sh")),
                "steps.$id.outputs.$name is never written to GITHUB_OUTPUT by $script.sh"
            )
        }
    }

    @Test
    fun `github page states the runner requirements and validate checks jq`() {
        val page = DocsSupport.read("docs/platforms/github.md")
        listOf("appKODE/karandash@v", "jq").forEach { assertContains(page, it) }
        listOf("PRIVATE-TOKEN", "read_api").forEach { assertFalse(page.contains(it), "github page still mentions '$it'") }
        assertContains(validate, "command -v jq")
    }

    @Test
    fun `failed validation run still reports passed false and guards the follow-up steps`() {
        assertContains(validate, "passed=false")
        assertContains(action, "hashFiles('karandash-results.sarif')")
        assertContains(action, "steps.validate.outputs.passed != ''")
    }
}
