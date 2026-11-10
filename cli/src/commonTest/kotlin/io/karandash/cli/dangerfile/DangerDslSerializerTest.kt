package io.karandash.cli.dangerfile

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DangerDslSerializerTest {
    private val sampleMr = MergeRequest(
        id = "1",
        iid = 42,
        title = "Fix login bug",
        description = "Fixes the login flow",
        sourceBranch = "fix/login",
        targetBranch = "main",
        author = User(username = "alice", name = "Alice Smith", email = "alice@example.com"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = listOf("bug", "priority:high"),
        commits = listOf(
            Commit(
                sha = "abc123",
                message = "fix login",
                author = CommitAuthor(name = "Alice Smith", email = "alice@example.com"),
                timestamp = "2024-01-15T10:00:00Z"
            )
        ),
        changes = listOf(
            FileChange(path = "src/login.kt", changeType = ChangeType.MODIFIED),
            FileChange(path = "src/new-file.kt", changeType = ChangeType.ADDED),
            FileChange(path = "src/old.kt", changeType = ChangeType.DELETED),
            FileChange(path = "src/renamed.kt", changeType = ChangeType.RENAMED),
        ),
        reviewers = listOf(
            User(username = "bob", name = "Bob Jones")
        )
    )

    @Test
    fun `serialize produces valid JSON`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val parsed = Json.parseToJsonElement(json)
        assertTrue(parsed is JsonObject, "Output must be a valid JSON object")
    }

    @Test
    fun `git block contains correct file arrays`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject
        val git = root["git"]!!.jsonObject

        assertEquals(
            listOf("src/login.kt"),
            git["modified_files"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("src/new-file.kt"),
            git["created_files"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("src/old.kt"),
            git["deleted_files"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("src/renamed.kt"),
            git["renamed_files"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `github pr block has correct fields`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject
        val pr = root["github"]!!.jsonObject["pr"]!!.jsonObject

        assertEquals("Fix login bug", pr["title"]!!.jsonPrimitive.content)
        assertEquals("Fixes the login flow", pr["body"]!!.jsonPrimitive.content)
        assertEquals("42", pr["number"]!!.jsonPrimitive.content)
        assertEquals("opened", pr["state"]!!.jsonPrimitive.content)
        assertEquals("false", pr["draft"]!!.jsonPrimitive.content)
        assertEquals("alice", pr["user"]!!.jsonObject["login"]!!.jsonPrimitive.content)
        assertEquals("fix/login", pr["head"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("main", pr["base"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gitlab mr block has correct fields`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject
        val mr = root["gitlab"]!!.jsonObject["mr"]!!.jsonObject

        assertEquals("Fix login bug", mr["title"]!!.jsonPrimitive.content)
        assertEquals("Fixes the login flow", mr["description"]!!.jsonPrimitive.content)
        assertEquals("42", mr["iid"]!!.jsonPrimitive.content)
        assertEquals("opened", mr["state"]!!.jsonPrimitive.content)
        assertEquals("false", mr["draft"]!!.jsonPrimitive.content)
        assertEquals("fix/login", mr["source_branch"]!!.jsonPrimitive.content)
        assertEquals("main", mr["target_branch"]!!.jsonPrimitive.content)
        assertEquals("Alice Smith", mr["author"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("alice", mr["author"]!!.jsonObject["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gitlab labels are string array`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject
        val labels = root["gitlab"]!!.jsonObject["mr"]!!.jsonObject["labels"]!!.jsonArray

        assertEquals(2, labels.size)
        assertEquals("bug", labels[0].jsonPrimitive.content)
        assertEquals("priority:high", labels[1].jsonPrimitive.content)
    }

    @Test
    fun `github labels are object array with name field`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject
        val labels = root["github"]!!.jsonObject["pr"]!!.jsonObject["labels"]!!.jsonArray

        assertEquals(2, labels.size)
        assertEquals("bug", labels[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `escapeJsonString handles special characters`() {
        val escaped = DangerDslSerializer.escapeJsonString("line1\nline2\ttab\"quote\\back")
        assertEquals("\"line1\\nline2\\ttab\\\"quote\\\\back\"", escaped)
    }

    @Test
    fun `escapeJsonString handles control characters`() {
        val escaped = DangerDslSerializer.escapeJsonString("null\u0000char")
        assertTrue(escaped.contains("\\u0000"))
    }

    @Test
    fun `escapeJsonString escapes the JS line terminators 2028 and 2029`() {
        assertEquals("\"a\\u2028b\\u2029c\"", DangerDslSerializer.escapeJsonString("a b c"))
    }

    @Test
    fun `serialize with null description uses empty string`() {
        val mr = sampleMr.copy(description = null)
        val json = DangerDslSerializer.serialize(mr)
        val root = Json.parseToJsonElement(json).jsonObject
        val pr = root["github"]!!.jsonObject["pr"]!!.jsonObject
        assertEquals("", pr["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `serialize with empty changes`() {
        val mr = sampleMr.copy(changes = emptyList())
        val json = DangerDslSerializer.serialize(mr)
        val root = Json.parseToJsonElement(json).jsonObject
        val git = root["git"]!!.jsonObject

        assertTrue(git["modified_files"]!!.jsonArray.isEmpty())
        assertTrue(git["created_files"]!!.jsonArray.isEmpty())
        assertTrue(git["deleted_files"]!!.jsonArray.isEmpty())
        assertTrue(git["renamed_files"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `flat fields are present for bridge script`() {
        val json = DangerDslSerializer.serialize(sampleMr)
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals("Fix login bug", root["title"]!!.jsonPrimitive.content)
        assertEquals("alice", root["authorLogin"]!!.jsonPrimitive.content)
        assertEquals("Alice Smith", root["authorName"]!!.jsonPrimitive.content)
        assertEquals("fix/login", root["sourceBranch"]!!.jsonPrimitive.content)
        assertEquals("main", root["targetBranch"]!!.jsonPrimitive.content)
    }
}
