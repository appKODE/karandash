package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.utils.VersionComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyValidatorTest {
    @Test
    fun `package json unpinned caret version produces warning`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "lodash": "^4.17.21"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("lodash"))
        assertTrue(findings[0].message.contains("^4.17.21"))
    }

    @Test
    fun `package json exact version produces no finding`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "lodash": "4.17.21"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `latest version produces warning`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "react": "latest"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("react"))
    }

    @Test
    fun `min version below required produces error`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            minVersions = mapOf("lodash" to "4.17.21"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "lodash": "4.16.0"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("lodash"))
        assertTrue(findings[0].message.contains("4.16.0"))
        assertTrue(findings[0].message.contains("4.17.21"))
    }

    @Test
    fun `min version equal to required produces no finding`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            minVersions = mapOf("lodash" to "4.17.21"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "lodash": "4.17.21"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `min version above required produces no finding`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            minVersions = mapOf("lodash" to "4.17.21"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "lodash": "4.18.0"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `blocked pattern name match produces error`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            blockedPatterns = listOf("evil-lib"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,3 +1,4 @@
             {
            +  "evil-lib": "1.0.0"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("evil-lib"))
    }

    @Test
    fun `blocked pattern name colon version match produces error`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            blockedPatterns = listOf("org.apache.logging.log4j:log4j-core:2.14"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            +implementation("org.apache.logging.log4j:log4j-core:2.14.1")
        """.trimIndent()
        val mr = mrWithChange("build.gradle.kts", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("log4j-core"))
    }

    @Test
    fun `no dependency files changed produces no findings`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val mr = mrWithChange("src/main/Foo.kt", "+fun foo() {}")

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `requirements txt pinned version produces no finding`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/requirements.txt
            +++ b/requirements.txt
            @@ -1 +1,2 @@
            +Django==3.2.0
        """.trimIndent()
        val mr = mrWithChange("requirements.txt", diff)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `requirements txt range version produces warning`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/requirements.txt
            +++ b/requirements.txt
            @@ -1 +1,2 @@
            +Flask>=2.0
        """.trimIndent()
        val mr = mrWithChange("requirements.txt", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("Flask"))
    }

    @Test
    fun `go mod dependency is parsed and checked`() {
        val config = DependencyValidationConfig(
            warnOnUnpinnedVersions = false,
            minVersions = mapOf("github.com/gin-gonic/gin" to "1.8.0"),
        )
        val validator = DependencyValidator(config)
        val diff = """
            --- a/go.mod
            +++ b/go.mod
            @@ -3 +3,4 @@
            +	github.com/gin-gonic/gin v1.7.0
        """.trimIndent()
        val mr = mrWithChange("go.mod", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("gin"))
    }

    @Test
    fun `build gradle kts dependency is parsed and checked`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = false, blockedPatterns = listOf("com.example:bad-lib:1.0"))
        val validator = DependencyValidator(config)
        val diff = """
            --- a/build.gradle.kts
            +++ b/build.gradle.kts
            @@ -5 +5,6 @@
            +    implementation("com.example:bad-lib:1.0.0")
        """.trimIndent()
        val mr = mrWithChange("build.gradle.kts", diff)

        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("com.example:bad-lib"))
    }

    @Test
    fun `isBelow returns false for equal versions`() {
        assertEquals(false, VersionComparator.isBelow("1.2.3", "1.2.3"))
    }

    @Test
    fun `isBelow returns true when patch is lower`() {
        assertEquals(true, VersionComparator.isBelow("1.2.2", "1.2.3"))
    }

    @Test
    fun `isBelow returns false when patch is higher`() {
        assertEquals(false, VersionComparator.isBelow("1.2.4", "1.2.3"))
    }

    @Test
    fun `isBelow returns true when major is lower`() {
        assertEquals(true, VersionComparator.isBelow("0.9.9", "1.0.0"))
    }

    @Test
    fun `diff with only removed lines produces no findings`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = true)
        val validator = DependencyValidator(config)
        val diff = """
            --- a/package.json
            +++ b/package.json
            @@ -1,4 +1,3 @@
             {
            -  "lodash": "^4.17.21"
             }
        """.trimIndent()
        val mr = mrWithChange("package.json", diff)

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `two part gradle group and name pattern blocks every version`() {
        val validator =
            DependencyValidator(DependencyValidationConfig(warnOnUnpinnedVersions = false, blockedPatterns = listOf("com.example:bad-lib")))

        val blocked = validator.validate(mrWithChange("build.gradle.kts", "+implementation(\"com.example:bad-lib:3.2.1\")"))
        val other = validator.validate(mrWithChange("build.gradle.kts", "+implementation(\"com.example:good-lib:3.2.1\")"))

        assertEquals(
            listOf(
                "Blocked dependency pattern 'com.example:bad-lib' matched by 'com.example:bad-lib:3.2.1' in build.gradle.kts"
            ),
            blocked.map {
                it.message
            }
        )
        assertTrue(other.isEmpty())
    }

    @Test
    fun `two part name and version prefix pattern still matches npm packages`() {
        val validator =
            DependencyValidator(DependencyValidationConfig(warnOnUnpinnedVersions = false, blockedPatterns = listOf("lodash:4.17")))
        val diff = { version: String -> "+  \"lodash\": \"$version\"" }

        assertEquals(1, validator.validate(mrWithChange("package.json", diff("4.17.20"))).size)
        assertTrue(validator.validate(mrWithChange("package.json", diff("4.18.0"))).isEmpty())
    }

    private fun mrWithChange(
        path: String,
        diffContent: String
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User", email = "test@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit(
                    sha = "abc123",
                    message = "test commit",
                    author = CommitAuthor(name = "Test User", email = "test@example.com"),
                    timestamp = "2024-01-01T00:00:00Z",
                ),
            ),
            changes = listOf(
                FileChange(
                    path = path,
                    changeType = ChangeType.MODIFIED,
                    diffContent = diffContent,
                ),
            ),
        )
}
