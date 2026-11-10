package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckRunAnnotationBuilderTest {
    private fun finding(
        severity: Severity = Severity.WARNING,
        rule: String = "test-rule",
        message: String = "test message",
        location: Location? = Location.FileRef("src/Foo.kt", 10),
    ) = Finding(severity = severity, message = message, rule = rule, location = location)

    @Test
    fun findingsWithFileRefProduceAnnotations() {
        val f = finding(location = Location.FileRef("src/Bar.kt", 5))
        val annotations = CheckRunAnnotationBuilder.build(listOf(f))
        assertEquals(1, annotations.size)
        assertEquals("src/Bar.kt", annotations[0].path)
        assertEquals(5, annotations[0].line)
        assertEquals(f.rule, annotations[0].title)
        assertEquals(f.message, annotations[0].message)
    }

    @Test
    fun findingsWithoutFileRefAreSkipped() {
        val withCommit = finding(location = Location.CommitRef("abc123"))
        val withNull = finding(location = null)
        val annotations = CheckRunAnnotationBuilder.build(listOf(withCommit, withNull))
        assertTrue(annotations.isEmpty())
    }

    @Test
    fun mixedLocationsOnlyFileRefAnnotated() {
        val file = finding(location = Location.FileRef("a.kt"))
        val commit = finding(location = Location.CommitRef("deadbeef"))
        val annotations = CheckRunAnnotationBuilder.build(listOf(file, commit))
        assertEquals(1, annotations.size)
        assertEquals("a.kt", annotations[0].path)
    }

    @Test
    fun severityMappedErrorToFailure() {
        val annotations = CheckRunAnnotationBuilder.build(listOf(finding(severity = Severity.ERROR)))
        assertEquals("failure", annotations[0].severity)
        assertEquals(Severity.ERROR, annotations[0].rawSeverity)
    }

    @Test
    fun severityMappedWarningToWarning() {
        val annotations = CheckRunAnnotationBuilder.build(listOf(finding(severity = Severity.WARNING)))
        assertEquals("warning", annotations[0].severity)
    }

    @Test
    fun severityMappedInfoToNotice() {
        val annotations = CheckRunAnnotationBuilder.build(listOf(finding(severity = Severity.INFO)))
        assertEquals("notice", annotations[0].severity)
    }

    @Test
    fun summaryFailureWhenErrorsPresent() {
        val findings = listOf(
            finding(severity = Severity.ERROR),
            finding(severity = Severity.WARNING),
        )
        val summary = CheckRunAnnotationBuilder.summary(findings)
        assertEquals("failure", summary.conclusion)
        assertEquals(1, summary.errors)
        assertEquals(1, summary.warnings)
    }

    @Test
    fun summaryNeutralWhenWarningsOnlyNoErrors() {
        val findings = listOf(finding(severity = Severity.WARNING))
        val summary = CheckRunAnnotationBuilder.summary(findings)
        assertEquals("neutral", summary.conclusion)
        assertEquals(0, summary.errors)
    }

    @Test
    fun summarySuccessWhenNoErrorsOrWarnings() {
        val findings = listOf(finding(severity = Severity.INFO))
        val summary = CheckRunAnnotationBuilder.summary(findings)
        assertEquals("success", summary.conclusion)
    }

    @Test
    fun summaryTotalAnnotationsCountsOnlyFileRefFindings() {
        val findings = listOf(
            finding(location = Location.FileRef("a.kt")),
            finding(location = Location.CommitRef("sha")),
            finding(location = null),
        )
        val summary = CheckRunAnnotationBuilder.summary(findings)
        assertEquals(1, summary.totalAnnotations)
    }

    @Test
    fun summaryTitleIsKarandashValidation() {
        val summary = CheckRunAnnotationBuilder.summary(emptyList())
        assertEquals("Karandash Validation", summary.title)
    }

    @Test
    fun emptyFindingsProduceSuccessSummary() {
        val summary = CheckRunAnnotationBuilder.summary(emptyList())
        assertEquals("success", summary.conclusion)
        assertEquals(0, summary.totalAnnotations)
    }
}
