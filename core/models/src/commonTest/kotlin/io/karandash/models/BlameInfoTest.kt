package io.karandash.models

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals

class BlameInfoTest {
    private val json = KarandashJson.Default

    @Test
    fun blameEntrySerializationRoundtrip() {
        val entry = BlameEntry(
            startLine = 1,
            endLine = 10,
            commit = BlameCommit(
                sha = "abc123",
                author = CommitAuthor(name = "Alice", email = "alice@example.com"),
                message = "Initial commit",
                timestamp = "2024-01-15T10:30:00Z",
            ),
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<BlameEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun blameCommitSerializationRoundtrip() {
        val commit = BlameCommit(
            sha = "def456",
            author = CommitAuthor(name = "Bob", email = "bob@example.com"),
            message = "Fix bug in parser",
            timestamp = "2024-03-20T14:00:00Z",
        )
        val encoded = json.encodeToString(commit)
        val decoded = json.decodeFromString<BlameCommit>(encoded)
        assertEquals(commit, decoded)
    }

    @Test
    fun fileChangeWithBlameDataSerializationRoundtrip() {
        val fileChange = FileChange(
            path = "src/main.kt",
            changeType = ChangeType.MODIFIED,
            additions = 5,
            deletions = 2,
            blameData = listOf(
                BlameEntry(
                    startLine = 1,
                    endLine = 5,
                    commit = BlameCommit(
                        sha = "aaa111",
                        author = CommitAuthor(name = "Alice", email = "alice@example.com"),
                        message = "Add main function",
                        timestamp = "2024-01-01T00:00:00Z",
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(fileChange)
        val decoded = json.decodeFromString<FileChange>(encoded)
        assertEquals(fileChange, decoded)
    }

    @Test
    fun fileChangeWithoutBlameDataDefaultsToEmptyList() {
        val fileChange = FileChange(
            path = "src/main.kt",
            changeType = ChangeType.ADDED,
        )
        assertEquals(emptyList(), fileChange.blameData)
    }

    @Test
    fun fileChangeWithoutBlameDataInJsonDeserializesWithEmptyList() {
        val jsonStr = """{"path":"test.kt","changeType":"MODIFIED","additions":1,"deletions":0}"""
        val decoded = json.decodeFromString<FileChange>(jsonStr)
        assertEquals(emptyList(), decoded.blameData)
    }
}
