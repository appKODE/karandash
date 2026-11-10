package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.ConfigParseException
import io.karandash.bootstrap.CliLogger
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Config errors print the problem, then the hint on its own `→ How to fix:` line, and exit with the config code.
 * a message carrying a catalog code renders through `ErrorRenderer`; one without a code prints as is, with no docs link.
 */
class ConfigErrorHintOutputTest {
    private suspend fun failWith(error: Exception): Pair<Int, List<String>> {
        val out = mutableListOf<String>()
        val result = assertFailsWith<ProgramResult> { runMappingErrors(CliLogger(sink = out::add)) { throw error } }
        return result.statusCode to out.map { it.substring(it.indexOf("[ERROR]")) }
    }

    @Test
    fun `a parse error is a config error and prints its hint`() =
        runTest {
            val (code, lines) = failWith(ConfigParseException("Top-level 'platforms:' must be an object", hint = "Rename it."))
            assertEquals(ExitCodes.CONFIG_ERROR, code)
            assertEquals(
                listOf("[ERROR] Top-level 'platforms:' must be an object\n  → How to fix: Rename it."),
                lines
            )
        }

    @Test
    fun `a config exception prints its hint and one without a hint prints the message only`() =
        runTest {
            val (code, lines) = failWith(ConfigException("missing.txt not found", cause = null, hint = "Create it."))
            assertEquals(ExitCodes.CONFIG_ERROR, code)
            assertEquals(listOf("[ERROR] missing.txt not found\n  → How to fix: Create it."), lines)
            assertEquals(listOf("[ERROR] bad"), failWith(ConfigException("bad")).second)
        }

    @Test
    fun `a catalog code in a config error renders its title suggestion and docs link`() =
        runTest {
            val error = KarandashError.CIRCULAR_CONFIG_INHERITANCE
            val (code, lines) = failWith(
                ConfigException("${error.bracketCode} Circular inheritance detected: a -> a", cause = null, hint = "Drop one.")
            )
            assertEquals(ExitCodes.CONFIG_ERROR, code)
            val text = lines.single()
            assertTrue(text.startsWith("[ERROR] E0010 Circular config inheritance\n"), text)
            assertTrue("  Circular inheritance detected: a -> a\n  → How to fix: Drop one.\n" in text, text)
            assertTrue("Suggestion: ${error.suggestion}" in text, text)
            assertTrue("See: ${error.docsUrl}" in text, text)
        }

    @Test
    fun `an uncoded provider error gets no docs link`() =
        runTest {
            val (code, lines) = failWith(ProviderException("Failed to fetch MR from gitlab: boom"))
            assertEquals(ExitCodes.PROVIDER_ERROR, code)
            assertEquals(listOf("[ERROR] Failed to fetch MR from gitlab: boom"), lines)
        }
}
