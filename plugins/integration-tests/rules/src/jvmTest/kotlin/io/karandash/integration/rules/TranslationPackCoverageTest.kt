package io.karandash.integration.rules

import io.karandash.testfixtures.TestFiles
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard: every English template of a built-in text table has an entry in the Polish and the Russian pack, so
 * `report.language: pl` or `ru` never falls back to English for a built-in text.
 */
class TranslationPackCoverageTest {
    @Test
    fun `every English template of the text tables has a Polish translation`() {
        val polish = PolishTranslationModule().translations()
        val missing = TEXT_TABLES.flatMap(::englishTemplates).filter { it !in polish && it !in UNTRANSLATED }.distinct()
        assertTrue(missing.isEmpty(), "English templates without a Polish entry: $missing")
    }

    @Test
    fun `every English template of the text tables has a Russian translation`() {
        val russian = RussianTranslationModule().translations()
        val missing = TEXT_TABLES.flatMap(::englishTemplates).filter { it !in russian }.distinct()
        assertTrue(missing.isEmpty(), "English templates without a Russian entry: $missing")
    }

    @Test
    fun `the guard lists every text table in the sources`() {
        val root = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }
        val tables = listOf("core", "plugins")
            .flatMap { commonMainSources(Paths.get(root.toString(), it)) }
            .flatMap { file ->
                val text = file.readText()
                val pkg = PACKAGE
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
                TABLE.findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList()
            }.toSet()
        assertEquals(tables, TEXT_TABLES.toSet())
    }

    /** The `.kt` files under a `src/commonMain` of [dir], skipping build output and hidden directories. */
    private fun commonMainSources(dir: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    path: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val name = path.fileName.toString()
                    return if (name == "build" || name.startsWith(".")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    path: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (path.extension == "kt" && "/src/commonMain/" in path.invariantSeparatorsPathString) files.add(path)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files
    }

    private fun englishTemplates(className: String): List<String> {
        val table = Class.forName(className)
        val en = table.getDeclaredField("en").apply { isAccessible = true }
        return table.enumConstants.map { en.get(it) as String }
    }

    private companion object {
        val PACKAGE = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
        val TABLE = Regex("""enum class (\w+)\(\s*(?:private |override )?val en: String,\s*\)""")

        /** English templates that stay English in Polish, each with its reason (Russian has none). */
        val UNTRANSLATED: Map<String, String> = emptyMap()

        val TEXT_TABLES = listOf(
            "io.karandash.models.finding.FixHintText",
            "io.karandash.validators.FileChangesText",
            "io.karandash.validators.DraftText",
            "io.karandash.validators.SecurityText",
            "io.karandash.validators.TitleFormatText",
            "io.karandash.validators.CommitMessagesText",
            "io.karandash.validators.CommitBodyText",
            "io.karandash.validators.LabelsText",
            "io.karandash.validators.SizeLimitsText",
            "io.karandash.validators.BranchNamingText",
            "io.karandash.validators.ReviewerText",
            "io.karandash.validators.DescriptionFormatText",
            "io.karandash.validators.CrossEntityText",
            "io.karandash.validators.LanguagePolicyText",
            "io.karandash.validators.CodeownersText",
            "io.karandash.validators.JiraTicketText",
            "io.karandash.validators.DependencyText",
            "io.karandash.validators.ApprovalText",
            "io.karandash.validators.ConflictText",
            "io.karandash.validators.ContextTagText",
            "io.karandash.validators.CicdStatusText",
            "io.karandash.plugins.ai.core.pipeline.CorrelationText",
            "io.karandash.helpers.reporter.ViewText",
            "io.karandash.plugins.formats.SummaryText",
            "io.karandash.plugins.formats.ConsoleText",
            "io.karandash.plugins.outputs.InlineLifecycleText",
            "io.karandash.plugins.outputs.StatusText",
            "io.karandash.plugins.ai.rules.PreMergeChecksText",
            "io.karandash.api.finding.CoverageText",
            "io.karandash.api.finding.SkipReasonText",
            "io.karandash.plugins.ai.AiStatusText",
            "io.karandash.plugins.ai.AiHealthText",
            "io.karandash.plugins.formats.HtmlText",
            "io.karandash.plugins.formats.SlackLayoutText",
            "io.karandash.plugins.ai.core.reporters.AiReportText",
            "io.karandash.plugins.outputs.EmailText",
            "io.karandash.plugins.ai.core.ThreadAnswerText",
            "io.karandash.plugins.ai.pipeline.GeneratedReportText",
            "io.karandash.plugins.ai.pipeline.ArchitectureCheckText",
        )
    }
}
