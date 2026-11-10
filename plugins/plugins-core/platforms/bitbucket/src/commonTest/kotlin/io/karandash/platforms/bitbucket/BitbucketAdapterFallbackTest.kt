package io.karandash.platforms.bitbucket

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sparse Bitbucket payloads fall back to the next field or to "unknown" instead of empty strings. */
class BitbucketAdapterFallbackTest {
    private val repo = BitbucketRepository(uuid = "{r}", name = "r", fullName = "w/r")

    private fun branch(name: String) = BitbucketBranch(BitbucketBranchInfo(name), BitbucketCommitRef("h-$name"), repo)

    private fun pr(
        author: BitbucketUser,
        participants: List<BitbucketParticipant> = emptyList(),
    ) = BitbucketPullRequest(
        id = 1,
        title = "t",
        state = "DRAFT",
        author = author,
        source = branch("f"),
        destination = branch("main"),
        createdOn = "c",
        updatedOn = "u",
        participants = participants,
        reviewers = listOf(BitbucketUser(uuid = "{v}", displayName = "Vera")),
    )

    private fun commit(raw: String) = BitbucketCommit("s", "d", "m", BitbucketCommitAuthor(raw = raw, user = null))

    @Test
    fun `username falls back to nickname then display name`() {
        val mr = BitbucketAdapter.toMergeRequest(
            pr(BitbucketUser(uuid = "{a}", displayName = "Ann", nickname = "annie")),
            emptyList(),
            emptyList()
        )
        assertEquals("annie", mr.author.username)
        assertEquals("Vera", mr.reviewers.single().username)
        assertEquals(true, mr.draft)
    }

    @Test
    fun `commit authors without name or email become unknown`() {
        val mr = BitbucketAdapter.toMergeRequest(
            pr(BitbucketUser(uuid = "{a}", displayName = "Ann")),
            listOf(commit("<only@mail>"), commit("Just Name"), commit("")),
            emptyList(),
        )
        assertEquals(listOf("unknown", "Just Name", "unknown"), mr.commits.map { it.author.name })
        assertEquals(listOf("only@mail", "unknown", "unknown"), mr.commits.map { it.author.email })
        val blame = BitbucketAdapter.toBlameEntries(listOf(commit("<only@mail>"), commit("")))
        assertEquals(listOf("unknown", "unknown"), blame.map { it.commit.author.name })
        assertEquals(listOf("only@mail", "unknown"), blame.map { it.commit.author.email })
    }

    @Test
    fun `diff stat path falls back to the old path then unknown`() {
        val stats = listOf(
            BitbucketDiffStat(status = "removed", oldPath = "gone.kt", newPath = null, linesAdded = 0, linesRemoved = 3),
            BitbucketDiffStat(status = "modified", oldPath = null, newPath = null, linesAdded = 1, linesRemoved = 1),
        )
        val mr = BitbucketAdapter.toMergeRequest(pr(BitbucketUser(uuid = "{a}", displayName = "Ann")), emptyList(), stats)
        assertEquals(listOf("gone.kt", "unknown"), mr.changes.map { it.path })
    }

    @Test
    fun `only approving participants become approvals`() {
        val participants = listOf(
            BitbucketParticipant(BitbucketUser(uuid = "{p}", displayName = "Pat"), "REVIEWER", approved = true),
            BitbucketParticipant(BitbucketUser(uuid = "{q}", displayName = "Quinn"), "REVIEWER", approved = false, participatedOn = "x"),
        )
        val mr = BitbucketAdapter.toMergeRequest(
            pr(BitbucketUser(uuid = "{a}", displayName = "Ann"), participants),
            emptyList(),
            emptyList()
        )
        assertEquals(listOf("Pat"), mr.approvals.map { it.user.name })
    }
}
