package io.karandash.plugins.ai.prompts

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateEngineTest {
    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Fix login bug",
            description = "Fixes timeout issue",
            sourceBranch = "fix/login",
            targetBranch = "main",
            author = User("dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = listOf("bugfix", "urgent"),
            commits = listOf(
                Commit("abc", "Fix login", CommitAuthor("Dev", "d@t.com"), "2024-01-01T00:00:00Z"),
            ),
            changes = listOf(
                FileChange("src/Login.kt", ChangeType.MODIFIED, additions = 5, deletions = 2),
                FileChange("src/Auth.kt", ChangeType.ADDED, additions = 10, deletions = 0),
            ),
        )

    @Test
    fun `render substitutes mr title`() {
        val result = PromptTemplateEngine.render("Review: {{mr.title}}", makeMr())
        assertEquals("Review: <untrusted-mr-title>Fix login bug</untrusted-mr-title>", result)
    }

    @Test
    fun `render substitutes mr description`() {
        val result = PromptTemplateEngine.render("Desc: {{mr.description}}", makeMr())
        assertEquals("Desc: <untrusted-mr-description>Fixes timeout issue</untrusted-mr-description>", result)
    }

    @Test
    fun `render substitutes branch names`() {
        val result = PromptTemplateEngine.render(
            "From {{mr.sourceBranch}} to {{mr.targetBranch}}",
            makeMr(),
        )
        assertEquals(
            "From <untrusted-mr-sourceBranch>fix/login</untrusted-mr-sourceBranch> to <untrusted-mr-targetBranch>main</untrusted-mr-targetBranch>",
            result
        )
    }

    @Test
    fun `render substitutes labels`() {
        val result = PromptTemplateEngine.render("Labels: {{mr.labels}}", makeMr())
        assertEquals("Labels: <untrusted-mr-labels>bugfix, urgent</untrusted-mr-labels>", result)
    }

    @Test
    fun `render substitutes counts`() {
        val result = PromptTemplateEngine.render(
            "{{mr.changes.count}} files, {{mr.commits.count}} commits",
            makeMr(),
        )
        assertEquals("2 files, 1 commits", result)
    }

    @Test
    fun `render substitutes additions and deletions`() {
        val result = PromptTemplateEngine.render(
            "+{{mr.additions}}/-{{mr.deletions}}",
            makeMr(),
        )
        assertEquals("+15/-2", result)
    }

    @Test
    fun `render substitutes author name`() {
        val result = PromptTemplateEngine.render("By {{mr.author}}", makeMr())
        assertEquals("By <untrusted-mr-author>Developer</untrusted-mr-author>", result)
    }

    @Test
    fun `render handles spaced variable syntax`() {
        val result = PromptTemplateEngine.render("Title: {{ mr.title }}", makeMr())
        assertEquals("Title: <untrusted-mr-title>Fix login bug</untrusted-mr-title>", result)
    }

    @Test
    fun `render leaves unknown variables intact`() {
        val result = PromptTemplateEngine.render("Value: {{unknown.var}}", makeMr())
        assertEquals("Value: {{unknown.var}}", result)
    }

    @Test
    fun `render applies extra overrides`() {
        val result = PromptTemplateEngine.render(
            "{{custom}} and {{mr.title}}",
            makeMr(),
            extra = mapOf("custom" to "OVERRIDE"),
        )
        assertEquals("OVERRIDE and <untrusted-mr-title>Fix login bug</untrusted-mr-title>", result)
    }

    @Test
    fun `buildVariables contains expected keys`() {
        val vars = PromptTemplateEngine.buildVariables(makeMr())
        assertTrue(vars.containsKey("mr.title"))
        assertTrue(vars.containsKey("mr.description"))
        assertTrue(vars.containsKey("mr.sourceBranch"))
        assertTrue(vars.containsKey("mr.targetBranch"))
        assertTrue(vars.containsKey("mr.draft"))
        assertTrue(vars.containsKey("mr.labels"))
        assertTrue(vars.containsKey("mr.changes.count"))
        assertTrue(vars.containsKey("mr.commits.count"))
        assertTrue(vars.containsKey("mr.author"))
        assertTrue(vars.containsKey("mr.additions"))
        assertTrue(vars.containsKey("mr.deletions"))
        assertTrue(vars.containsKey("mr.files"))
    }

    @Test
    fun `render with null description returns empty string`() {
        val mr = makeMr().copy(description = null)
        val result = PromptTemplateEngine.render("Desc: {{mr.description}}", mr)
        assertEquals("Desc: <untrusted-mr-description></untrusted-mr-description>", result)
    }

    // --- Conditional engine tests ---

    @Test
    fun `renderConditionals includes block when condition is true`() {
        val vars = mapOf("mr.draft" to "true")
        val template = "{% if mr.draft == true %}Draft notice.{% endif %}"
        val result = PromptTemplateEngine.renderConditionals(template, vars)
        assertEquals("Draft notice.", result)
    }

    @Test
    fun `renderConditionals removes block when condition is false`() {
        val vars = mapOf("mr.draft" to "false")
        val template = "{% if mr.draft == true %}Draft notice.{% endif %}"
        val result = PromptTemplateEngine.renderConditionals(template, vars)
        assertEquals("", result)
    }

    @Test
    fun `renderConditionals evaluates numeric greater-than`() {
        val vars = mapOf("mr.changes.count" to "15")
        val trueResult = PromptTemplateEngine.renderConditionals(
            "{% if mr.changes.count > 10 %}large{% endif %}",
            vars
        )
        assertEquals("large", trueResult)

        val falseResult = PromptTemplateEngine.renderConditionals(
            "{% if mr.changes.count > 20 %}large{% endif %}",
            vars
        )
        assertEquals("", falseResult)
    }

    @Test
    fun `renderConditionals evaluates contains operator`() {
        val vars = mapOf("mr.labels" to "bugfix, hotfix, urgent")
        val result = PromptTemplateEngine.renderConditionals(
            "{% if mr.labels contains hotfix %}HOTFIX!{% endif %}",
            vars
        )
        assertEquals("HOTFIX!", result)
    }

    @Test
    fun `renderConditionals evaluates not-equal operator`() {
        val vars = mapOf("mr.targetBranch" to "main")
        val result = PromptTemplateEngine.renderConditionals(
            "{% if mr.targetBranch != develop %}targeting main{% endif %}",
            vars
        )
        assertEquals("targeting main", result)
    }

    @Test
    fun `renderConditionals leaves block intact for unknown variable`() {
        val vars = mapOf<String, String>()
        val template = "{% if mr.unknown == foo %}content{% endif %}"
        val result = PromptTemplateEngine.renderConditionals(template, vars)
        assertEquals(template, result)
    }

    @Test
    fun `render processes if block end-to-end with variable substitution`() {
        val mr = makeMr().copy(draft = true)
        val template = "{% if mr.draft == true %}Draft: {{mr.title}}{% endif %}"
        val result = PromptTemplateEngine.render(template, mr)
        assertEquals("Draft: <untrusted-mr-title>Fix login bug</untrusted-mr-title>", result)
    }

    // --- Loop engine tests ---

    @Test
    fun `renderLoops expands for block over list variable`() {
        val vars = mapOf("mr.files" to "src/A.kt, src/B.kt, src/C.kt")
        val template = "{% for file in mr.files %}- {{file}}\n{% endfor %}"
        val result = PromptTemplateEngine.renderLoops(template, vars)
        assertEquals("- src/A.kt\n- src/B.kt\n- src/C.kt\n", result)
    }

    @Test
    fun `renderLoops produces empty string for unknown list variable`() {
        val vars = mapOf<String, String>()
        val result = PromptTemplateEngine.renderLoops(
            "{% for file in mr.files %}- {{file}}\n{% endfor %}",
            vars
        )
        assertEquals("", result)
    }

    @Test
    fun `render processes for loop end-to-end`() {
        val mr = makeMr()
        val template = "Files:\n{% for file in mr.files %}- {{file}}\n{% endfor %}"
        val result = PromptTemplateEngine.render(template, mr)
        assertEquals(
            "Files:\n- <untrusted-mr-files>src/Login.kt</untrusted-mr-files>\n- <untrusted-mr-files>src/Auth.kt</untrusted-mr-files>\n",
            result
        )
    }

    // --- evaluateCondition unit tests ---

    @Test
    fun `evaluateCondition handles equality`() {
        val vars = mapOf("mr.targetBranch" to "main")
        assertEquals(true, PromptTemplateEngine.evaluateCondition("mr.targetBranch == main", vars))
        assertEquals(false, PromptTemplateEngine.evaluateCondition("mr.targetBranch == develop", vars))
    }

    @Test
    fun `evaluateCondition handles numeric comparisons`() {
        val vars = mapOf("mr.changes.count" to "5")
        assertEquals(true, PromptTemplateEngine.evaluateCondition("mr.changes.count >= 5", vars))
        assertEquals(true, PromptTemplateEngine.evaluateCondition("mr.changes.count <= 5", vars))
        assertEquals(false, PromptTemplateEngine.evaluateCondition("mr.changes.count > 5", vars))
        assertEquals(false, PromptTemplateEngine.evaluateCondition("mr.changes.count < 5", vars))
    }

    @Test
    fun `evaluateCondition returns null for unparseable expression`() {
        val vars = mapOf("mr.title" to "test")
        assertEquals(null, PromptTemplateEngine.evaluateCondition("invalid expression !!!", vars))
    }

    // --- Template injection security tests ---

    @Test
    fun `render does not expand template syntax embedded in mr title`() {
        val mr = makeMr().copy(title = "{{mr.description}}")
        val result = PromptTemplateEngine.render("Title: {{mr.title}}", mr)
        assertEquals("Title: <untrusted-mr-title>{{mr.description}}</untrusted-mr-title>", result)
    }

    @Test
    fun `render does not process conditional syntax embedded in mr description`() {
        val mr = makeMr().copy(description = "{% if mr.draft == true %}secret{% endif %}")
        val result = PromptTemplateEngine.render("Desc: {{mr.description}}", mr)
        assertEquals("Desc: <untrusted-mr-description>{% if mr.draft == true %}secret{% endif %}</untrusted-mr-description>", result)
    }

    @Test
    fun `render preserves literal double-brace characters in mr title`() {
        val mr = makeMr().copy(title = "Fix {{bug}} in auth")
        val result = PromptTemplateEngine.render("Title: {{mr.title}}", mr)
        assertEquals("Title: <untrusted-mr-title>Fix {{bug}} in auth</untrusted-mr-title>", result)
    }

    @Test
    fun `renderSplit keeps references in the text and the MR values in mrData`() {
        val mr = makeMr().copy(title = "Fix {{bug}} in auth")
        val result = PromptTemplateEngine.renderSplit(
            "Title: {{mr.title}} on {{mr.targetBranch}}\n{% for file in mr.files %}- {{file}}\n{% endfor %}+{{mr.additions}}",
            mr,
        )

        assertEquals("Title: <untrusted-mr-title/> on <untrusted-mr-targetBranch/>\n- <untrusted-mr-files/>\n+15", result.text)
        assertEquals(
            "<untrusted-mr-files>src/Login.kt, src/Auth.kt</untrusted-mr-files>\n" +
                "<untrusted-mr-title>Fix {{bug}} in auth</untrusted-mr-title>\n" +
                "<untrusted-mr-targetBranch>main</untrusted-mr-targetBranch>",
            result.mrData,
        )
    }

    @Test
    fun `renderSplit without MR placeholders has no mrData`() {
        assertEquals(RenderedPrompt("Review +15", null), PromptTemplateEngine.renderSplit("Review +{{mr.additions}}", makeMr()))
    }

    @Test
    fun `evaluateCondition handles numeric equality and rejects ordering on text`() {
        val vars = mapOf("n" to "5", "t" to "abc")
        assertEquals(true, PromptTemplateEngine.evaluateCondition("n == 5.0", vars))
        assertEquals(false, PromptTemplateEngine.evaluateCondition("n != 5", vars))
        assertEquals(true, PromptTemplateEngine.evaluateCondition("n <= 5", vars))
        assertEquals(false, PromptTemplateEngine.evaluateCondition("n < 5", vars))
        assertEquals(true, PromptTemplateEngine.evaluateCondition("n >= 5", vars))
        assertNull(PromptTemplateEngine.evaluateCondition("t > abc", vars))
        assertNull(PromptTemplateEngine.evaluateCondition("t is abc", vars))
    }

    @Test
    fun `an unresolved placeholder is kept and warned once`() {
        val warnings = mutableListOf<String>()
        val mr = makeMr().copy(title = "{{evil}}")
        val result = PromptTemplateEngine.render("{{nope}} {{ nope }} {{mr.title}}", mr, warn = { warnings += it })
        assertTrue(result.startsWith("{{nope}} {{ nope }}"), result)
        assertEquals(listOf("Prompt template placeholders not resolved (left as-is): nope"), warnings)
    }
}
