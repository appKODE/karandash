package io.karandash.plugins.ai.core.incremental

import io.karandash.cache.cas.CasCache
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.sdk.Logger
import io.karandash.testfixtures.TestFiles
import io.karandash.utils.finding.AnchorFingerprint
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncrementalValidatorTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private lateinit var tempDir: Path
    private lateinit var casCache: CasCache

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-incremental-test")
        SystemFileSystem.createDirectories(tempDir)
        casCache = CasCache(baseDir = tempDir, namespace = "state")
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    private fun makePersistence(): AiStatePersistence = AiStatePersistence(casCache)

    private fun makeMr(
        id: String = "1",
        changes: List<FileChange> = emptyList(),
        title: String = "Test MR",
        description: String? = null,
        commits: List<Commit> = listOf(
            Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z"),
        ),
    ) = MergeRequest(
        id = id,
        iid = 1,
        title = title,
        description = description,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = commits,
        changes = changes,
    )

    private fun makeChange(
        path: String,
        diff: String = "some diff",
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = diff,
    )

    private fun aiFinding(
        path: String,
        line: Int? = 1,
    ) = Finding(
        severity = Severity.WARNING,
        message = "AI issue",
        rule = "ai-review",
        location = Location.FileRef(path = path, line = line),
    )

    private fun mrLevelFinding(rule: String = "risk-scoring") =
        Finding(
            severity = Severity.INFO,
            message = "MR-level AI finding",
            rule = rule,
            location = Location.CommitRef(sha = "abc123"),
        )

    private fun nonAiFinding(path: String) =
        Finding(
            severity = Severity.WARNING,
            message = "Non-AI issue",
            rule = "title-format",
            location = Location.FileRef(path = path, line = 1),
        )

    @Test
    fun prepareWithNoPreviousSnapshotReturnsAllFilesAndNoCachedFindings() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt"), makeChange("src/Bar.kt")))

        val ctx = validator.prepare(mr)

        assertEquals(2, ctx.deltaMr.changes.size)
        assertTrue(ctx.unchangedFiles.isEmpty())
        assertTrue(ctx.cachedFindings.isEmpty())
        assertFalse(ctx.isFullyCached)
    }

    @Test
    fun prepareAfterSaveWithUnchangedFilesReturnsCachedFindings() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-content")))
        val finding = aiFinding("src/Foo.kt")

        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(finding))

        val ctx2 = validator.prepare(mr)
        assertEquals(1, ctx2.cachedFindings.size)
        assertEquals(finding, ctx2.cachedFindings.first())
        assertTrue(ctx2.unchangedFiles.contains("src/Foo.kt"))
        assertTrue(ctx2.deltaMr.changes.isEmpty())
        assertTrue(ctx2.isFullyCached)
    }

    @Test
    fun isFullyCachedIsFalseWhenDeltaHasChangesEvenIfSomeCachedFindingsExist() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v1")))
        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(aiFinding("src/Foo.kt")))

        val mr2 = makeMr(
            changes = listOf(
                makeChange("src/Foo.kt", "diff-v1"),
                makeChange("src/Bar.kt", "new-diff"),
            ),
        )
        val ctx2 = validator.prepare(mr2)

        assertFalse(ctx2.isFullyCached)
        assertEquals(1, ctx2.deltaMr.changes.size)
        assertEquals(
            "src/Bar.kt",
            ctx2.deltaMr.changes
                .first()
                .path
        )
        assertEquals(listOf("src/Foo.kt"), ctx2.unchangedFiles)
        assertEquals(1, ctx2.cachedFindings.size)
    }

    @Test
    fun savePeristsSnapshotSoSubsequentPrepareDetectsUnchangedFiles() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/A.kt", "diff-a")))

        val ctx = validator.prepare(mr)
        validator.save(ctx, emptyList())

        val snap = persistence.loadSnapshot(SnapshotManager.mrKey(mr))
        assertTrue(snap.containsKey("src/A.kt"))
        assertEquals(io.karandash.crypto.sha256First64Bits("diff-a"), snap["src/A.kt"])
    }

    @Test
    fun saveCachesAllFileRefFindings() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))

        val ctx = validator.prepare(mr)
        val aiF = aiFinding("src/Foo.kt")
        val nonAiF = nonAiFinding("src/Foo.kt")
        validator.save(ctx, listOf(aiF, nonAiF))

        val cached = persistence.loadFindingsCache(SnapshotManager.mrKey(mr))
        val fooCached = cached["src/Foo.kt"] ?: emptyList()
        assertEquals(2, fooCached.size)
        assertTrue(fooCached.contains(aiF))
        assertTrue(fooCached.contains(nonAiF))
    }

    @Test
    fun saveCachesMrLevelFindingsUnderSyntheticKey() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))

        val ctx = validator.prepare(mr)
        val commitFinding = mrLevelFinding()
        validator.save(ctx, listOf(commitFinding))

        val cached = persistence.loadFindingsCache(SnapshotManager.mrKey(mr))
        val mrLevel = cached[IncrementalValidator.MR_LEVEL_KEY] ?: emptyList()
        assertEquals(1, mrLevel.size)
        assertEquals(commitFinding, mrLevel.first())
    }

    @Test
    fun loadCachedFindingsReturnsBothFileLevelAndMrLevelFindings() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val fileFinding = aiFinding("src/Foo.kt")
        val mrFinding = mrLevelFinding()

        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(fileFinding, mrFinding))

        val ctx2 = validator.prepare(mr)
        assertEquals(2, ctx2.cachedFindings.size)
        assertTrue(ctx2.cachedFindings.contains(fileFinding))
        assertTrue(ctx2.cachedFindings.contains(mrFinding))
        assertTrue(ctx2.isFullyCached)
    }

    @Test
    fun metadataChangeInvalidatesMrLevelCache() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Original title",
        )
        val fileFinding = aiFinding("src/Foo.kt")
        val mrFinding = mrLevelFinding()

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(fileFinding, mrFinding))

        val mr2 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Changed title",
        )
        val ctx2 = validator.prepare(mr2)

        // File diff unchanged → file stays in unchangedFiles, file finding is preserved
        assertEquals(listOf("src/Foo.kt"), ctx2.unchangedFiles)
        assertTrue(ctx2.deltaMr.changes.isEmpty())
        // MR-level finding is NOT loaded - metadata changed, must re-evaluate MR-level rules
        assertEquals(listOf(fileFinding), ctx2.cachedFindings)
        // Not fully cached - metadata changed means MR-level must be re-run
        assertFalse(ctx2.isFullyCached)
        assertTrue(ctx2.metadataChanged)
    }

    @Test
    fun descriptionOnlyChangeForcesFullReReview() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            description = "Original desc",
        )
        val fileFinding = aiFinding("src/Foo.kt")

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(fileFinding, mrLevelFinding()))

        val mr2 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            description = "Updated desc",
        )
        val ctx2 = validator.prepare(mr2)

        // File diff unchanged → preserved in unchangedFiles with its cached finding
        assertEquals(listOf("src/Foo.kt"), ctx2.unchangedFiles)
        // Only file-level finding is cached; MR-level is dropped (description changed)
        assertEquals(listOf(fileFinding), ctx2.cachedFindings)
        assertFalse(ctx2.isFullyCached)
        assertTrue(ctx2.metadataChanged)
    }

    @Test
    fun prepareReturnsEmptyCachedFindingsWhenNoSaveHasOccurred() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))

        val ctx = validator.prepare(mr)

        assertTrue(ctx.cachedFindings.isEmpty())
        assertFalse(ctx.isFullyCached)
    }

    @Test
    fun isFullyCachedRequiresBothEmptyDeltaAndNonEmptyCachedFindings() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))

        // Save with empty findings - snapshot is persisted so hasCacheEntry = true
        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, emptyList())

        val ctx2 = validator.prepare(mr)
        // delta is empty (file unchanged) and a cache entry exists → fully cached even with zero findings
        assertTrue(ctx2.deltaMr.changes.isEmpty())
        assertTrue(ctx2.cachedFindings.isEmpty())
        assertTrue(ctx2.hasCacheEntry)
        assertTrue(ctx2.isFullyCached)
    }

    @Test
    fun partialUpdatePreservesCachedFindingsForUnchangedFiles() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val findingA = aiFinding("src/A.kt")
        val findingB = aiFinding("src/B.kt")

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(findingA, findingB))

        // Change only A, keep B unchanged
        val mr2 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a-new"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val findingANew = aiFinding("src/A.kt", line = 5)
        val ctx2 = validator.prepare(mr2)
        validator.save(ctx2, listOf(findingANew))

        // Prepare again on the same MR - B's finding must survive, A gets the new finding
        val validator2 = IncrementalValidator(AiStatePersistence(casCache), noopLogger)
        val ctx3 = validator2.prepare(mr2)
        assertTrue(ctx3.cachedFindings.any { it.location is Location.FileRef && (it.location as Location.FileRef).path == "src/B.kt" })
        assertTrue(ctx3.cachedFindings.any { it == findingANew })
        assertFalse(ctx3.cachedFindings.any { it == findingA }) // old A finding gone
    }

    @Test
    fun mrLevelFindingsPreservedOnPartialCacheHit() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val fileFinding = aiFinding("src/A.kt")
        val mrFinding = mrLevelFinding()

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(fileFinding, mrFinding))

        // Change only A - B is unchanged, MR metadata unchanged
        val mr2 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a-new"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val ctx2 = validator.prepare(mr2)

        // MR-level finding must be in cachedFindings on partial cache hit
        assertTrue(ctx2.cachedFindings.any { it == mrFinding })
    }

    @Test
    fun metadataChangeRoundTripRefreshesMrLevelFindings() {
        // MR-level rules (mr-summary, risk-scoring, review-thread-analysis) run when only MR metadata
        // changes: the freshly produced MR-level finding must replace the stale cached one.
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)

        val mr1 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Original title",
        )
        val staleMrFinding = Finding(
            severity = Severity.INFO,
            message = "Summary for original title",
            rule = "mr-summary",
            location = Location.CommitRef(sha = "abc123"),
        )
        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(aiFinding("src/Foo.kt"), staleMrFinding))

        // Second run: title changed, file diffs unchanged
        val mr2 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Updated title",
        )
        val freshMrFinding = Finding(
            severity = Severity.INFO,
            message = "Summary for updated title",
            rule = "mr-summary",
            location = Location.CommitRef(sha = "abc123"),
        )
        val ctx2 = validator.prepare(mr2)
        assertTrue(ctx2.metadataChanged)
        // The fresh MR-level finding was produced because the rule re-ran against the new title.
        validator.save(ctx2, listOf(freshMrFinding))

        // Third prepare (same updated MR, now cached): the fresh MR-level finding is loaded, the stale one is gone.
        val validator2 = IncrementalValidator(AiStatePersistence(casCache), noopLogger)
        val ctx3 = validator2.prepare(mr2)
        val mrLevelFindings = ctx3.cachedFindings.filter { it.location is Location.CommitRef }
        assertEquals(listOf(freshMrFinding), mrLevelFindings)
        assertFalse(ctx3.metadataChanged)
        // File-level finding for the unchanged file is also preserved across the metadata-change round trip.
        assertTrue(ctx3.cachedFindings.any { it.rule == "ai-review" })
    }

    @Test
    fun priorFindingsForChangedFilesAreExposedAfterResave() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)

        val mr1 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v1")))
        val priorFooFinding = aiFinding("src/Foo.kt", line = 42)
        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(priorFooFinding))

        // File changes in the second run - its cache entry should now be surfaced as "prior" for the prompt.
        val mr2 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v2")))
        val ctx2 = validator.prepare(mr2)

        assertTrue("src/Foo.kt" in ctx2.priorFindingsByChangedFile)
        assertEquals(listOf(priorFooFinding), ctx2.priorFindingsByChangedFile["src/Foo.kt"])
        // File is in the delta so cachedFindings does NOT restore it into the report path.
        assertFalse(ctx2.cachedFindings.contains(priorFooFinding))
    }

    @Test
    fun priorMrLevelFindingsLoadedEvenWhenMetadataChanged() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)

        val mr1 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Original title",
        )
        val priorMrFinding = mrLevelFinding()
        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(aiFinding("src/Foo.kt"), priorMrFinding))

        // Only metadata changes - cachedFindings (restore-into-report path) must NOT carry MR-level,
        // but the priorMrLevelFindings (prompt-context path) MUST.
        val mr2 = makeMr(
            changes = listOf(makeChange("src/Foo.kt", "diff")),
            title = "Renamed title",
        )
        val ctx2 = validator.prepare(mr2)

        assertTrue(ctx2.metadataChanged)
        assertFalse(ctx2.cachedFindings.contains(priorMrFinding))
        assertEquals(listOf(priorMrFinding), ctx2.priorMrLevelFindings)
    }

    @Test
    fun priorFindingsEmptyOnFirstRun() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val ctx = validator.prepare(mr)
        assertTrue(ctx.priorFindingsByChangedFile.isEmpty())
        assertTrue(ctx.priorMrLevelFindings.isEmpty())
    }

    @Test
    fun mrLevelFindingsClearedWhenMetadataChanges() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(makeChange("src/A.kt", "diff-a")),
            title = "Original title",
        )
        val mrFinding = mrLevelFinding()

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(aiFinding("src/A.kt"), mrFinding))

        val mr2 = makeMr(
            changes = listOf(makeChange("src/A.kt", "diff-a")),
            title = "Changed title",
        )
        val ctx2 = validator.prepare(mr2)

        // Metadata change triggers full re-analysis - MR-level finding must not appear
        assertFalse(ctx2.cachedFindings.any { it == mrFinding })
    }

    @Test
    fun staleRuleFindingsClearedWhenRuleStopsProducing() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mrV1 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v1")))

        // First run produces a draftPrevention finding for Foo.kt
        val ctx1 = validator.prepare(mrV1)
        val draftFinding = Finding(
            severity = io.karandash.models.finding.Severity.ERROR,
            message = "Draft MR",
            rule = "draftPrevention",
            location = Location.FileRef("src/Foo.kt"),
        )
        validator.save(ctx1, listOf(draftFinding))

        // Second run: file diff changed → Foo.kt is in the delta and re-analyzed → zero findings
        val mrV2 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v2")))
        val ctx2 = validator.prepare(mrV2)
        assertEquals(1, ctx2.deltaMr.changes.size) // Foo.kt is in delta
        validator.save(ctx2, emptyList())

        // Third prepare on same v2 MR: draftPrevention must be gone (rule stopped producing)
        val ctx3 = validator.prepare(mrV2)
        assertFalse(ctx3.cachedFindings.any { it.rule == "draftPrevention" })
    }

    @Test
    fun isFullyCachedTrueForCleanMrWithZeroFindings() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/A.kt", "diff-a")))

        // First run: no findings
        val ctx1 = validator.prepare(mr)
        assertFalse(ctx1.hasCacheEntry)
        assertFalse(ctx1.isFullyCached)
        validator.save(ctx1, emptyList())

        // Second run: same MR, zero findings cached - should be fully cached
        val ctx2 = validator.prepare(mr)
        assertTrue(ctx2.hasCacheEntry)
        assertTrue(ctx2.deltaMr.changes.isEmpty())
        assertTrue(ctx2.isFullyCached)
    }

    @Test
    fun repeatedFullCacheHitsDoNotMutateFindings() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val finding = aiFinding("src/Foo.kt")

        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(finding))

        // Multiple prepare calls on fully-cached MR without saving
        val ctx2 = validator.prepare(mr)
        assertTrue(ctx2.isFullyCached)

        val ctx3 = validator.prepare(mr)
        assertTrue(ctx3.isFullyCached)

        // Findings are stable across repeated reads
        assertEquals(ctx2.cachedFindings, ctx3.cachedFindings)
        assertEquals(1, ctx3.cachedFindings.size)
        assertEquals(finding, ctx3.cachedFindings.first())
    }

    @Test
    fun deletedFileDoesNotLeaveStaleFindingsInCache() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr1 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val findingA = aiFinding("src/A.kt")
        val findingB = aiFinding("src/B.kt")

        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(findingA, findingB))

        // B is removed from the MR
        val mr2 = makeMr(changes = listOf(makeChange("src/A.kt", "diff-a")))
        val ctx2 = validator.prepare(mr2)
        validator.save(ctx2, listOf(findingA))

        // Verify B's findings are gone from the persistence layer
        val cached = persistence.loadFindingsCache(SnapshotManager.mrKey(mr2))
        assertFalse(cached.containsKey("src/B.kt"))
        assertTrue(cached.containsKey("src/A.kt"))
    }

    @Test
    fun budgetExhaustedSkipsSaveSoPreviousCacheIsPreserved() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v1")))
        val finding = aiFinding("src/Foo.kt")

        // Run 1: save findings normally
        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(finding))

        // Run 2: file changed, but budget exhausted - save should be skipped
        val mr2 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v2")))
        val ctx2 = validator.prepare(mr2)
        assertEquals(1, ctx2.deltaMr.changes.size) // Foo.kt is in delta
        validator.save(ctx2, emptyList(), budgetExhausted = true)

        // Run 3: prepare again with v2 - cache should still have old snapshot (v1),
        // so Foo.kt appears as changed (hash mismatch between v1 snapshot and v2 content)
        val ctx3 = validator.prepare(mr2)
        assertEquals(1, ctx3.deltaMr.changes.size)
        // The old finding from v1 is NOT loaded for Foo.kt because the file changed
        assertTrue(ctx3.cachedFindings.isEmpty())
    }

    private fun ruleFinding(
        rule: String,
        path: String,
    ) = aiFinding(path).copy(rule = rule, message = "$rule issue")

    @Test
    fun `provider errors on 2 of 4 rules with no prior cache save the 2 that completed`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/A.kt"), makeChange("src/B.kt")))
        val failed = setOf("ai-r3", "ai-r4")

        val ctx1 = validator.prepare(mr)
        assertTrue(ctx1.firstRun)
        val run1 = listOf(ruleFinding("ai-r1", "src/A.kt"), ruleFinding("ai-r2", "src/B.kt"), ruleFinding("ai-r3", "src/A.kt"))
        validator.save(ctx1, run1, budgetExhausted = true, incompleteRules = failed)

        val ctx2 = validator.prepare(mr)
        assertFalse(ctx2.firstRun)
        assertEquals(failed, ctx2.pendingRules)
        assertFalse(ctx2.hasCacheEntry, "a pending rule keeps the MR from being a full hit")
        assertFalse(ctx2.isFullyCached)
        assertEquals(setOf("ai-r1", "ai-r2"), ctx2.cachedFindings.map { it.rule }.toSet())

        validator.save(ctx2, listOf(ruleFinding("ai-r3", "src/B.kt")))
        val ctx3 = validator.prepare(mr)
        assertTrue(ctx3.pendingRules.isEmpty())
        assertTrue(ctx3.isFullyCached)
        assertEquals(setOf("ai-r1", "ai-r2", "ai-r3"), ctx3.cachedFindings.map { it.rule }.toSet())
    }

    @Test
    fun `no rule completing with a prior cache keeps the prior cache`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/A.kt", "v1")))
        validator.save(validator.prepare(mr), listOf(ruleFinding("ai-r1", "src/A.kt")))

        val mr2 = makeMr(changes = listOf(makeChange("src/A.kt", "v2")))
        validator.save(validator.prepare(mr2), emptyList(), budgetExhausted = true, incompleteRules = setOf("ai-r1"))

        val ctx = validator.prepare(mr)
        assertTrue(ctx.isFullyCached, "the v1 state is untouched")
        assertTrue(ctx.pendingRules.isEmpty())
    }

    @Test
    fun cleanReviewWithZeroFindingsDoesOverwriteCache() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v1")))
        val finding = aiFinding("src/Foo.kt")

        // Run 1: save findings normally
        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(finding))

        // Run 2: file changed, clean review (not budget exhausted)
        val mr2 = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff-v2")))
        val ctx2 = validator.prepare(mr2)
        validator.save(ctx2, emptyList(), budgetExhausted = false)

        // Run 3: prepare again - Foo.kt unchanged (v2), cache has empty findings
        val ctx3 = validator.prepare(mr2)
        assertTrue(ctx3.isFullyCached)
        assertTrue(ctx3.cachedFindings.isEmpty())
    }

    @Test
    fun changedFilesReturnsPathsOfChangedFiles() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr1 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val ctx1 = validator.prepare(mr1)
        validator.save(ctx1, listOf(aiFinding("src/A.kt"), aiFinding("src/B.kt")))

        // Change only A
        val mr2 = makeMr(
            changes = listOf(
                makeChange("src/A.kt", "diff-a-new"),
                makeChange("src/B.kt", "diff-b"),
            ),
        )
        val ctx2 = validator.prepare(mr2)
        assertEquals(setOf("src/A.kt"), ctx2.changedFiles)
    }

    @Test
    fun isFullyCachedReturnsEmptyChangedFiles() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(aiFinding("src/Foo.kt")))

        val ctx2 = validator.prepare(mr)
        assertTrue(ctx2.isFullyCached)
        assertTrue(ctx2.changedFiles.isEmpty())
    }

    @Test
    fun configHashChangeInvalidatesTheWholeCache() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val ctx1 = validator.prepare(mr, configHash = 1L)
        validator.save(ctx1, listOf(aiFinding("src/Foo.kt"), mrLevelFinding()))

        val sameConfig = validator.prepare(mr, configHash = 1L)
        assertTrue(sameConfig.isFullyCached)

        val changedConfig = validator.prepare(mr, configHash = 2L)
        assertFalse(changedConfig.isFullyCached)
        assertTrue(changedConfig.cachedFindings.isEmpty())
        assertEquals(1, changedConfig.deltaMr.changes.size)
        assertTrue(changedConfig.metadataChanged)
    }

    @Test
    fun configChangeWithNoFreshMrLevelFindingsDropsCachedOnes() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val ctx1 = validator.prepare(mr, configHash = 1L)
        validator.save(ctx1, listOf(mrLevelFinding()))

        val ctx2 = validator.prepare(mr, configHash = 2L)
        validator.save(ctx2, emptyList())

        val cached = persistence.loadFindingsCache(SnapshotManager.mrKey(mr))
        assertTrue(cached.values.flatten().none { it.location !is Location.FileRef }, cached.toString())
    }

    @Test
    fun freshMrLevelFindingsReplaceCachedOnesOfTheSameRule() {
        val persistence = makePersistence()
        val validator = IncrementalValidator(persistence, noopLogger)
        val mr = makeMr(changes = listOf(makeChange("src/Foo.kt", "diff")))
        val old = mrLevelFinding().copy(message = "risk 5")
        val other = mrLevelFinding(rule = "ticket-compliance")
        val ctx1 = validator.prepare(mr)
        validator.save(ctx1, listOf(old, other))

        val ctx2 = validator.prepare(makeMr(changes = listOf(makeChange("src/Foo.kt", "diff"), makeChange("src/Bar.kt"))))
        val fresh = mrLevelFinding().copy(message = "risk 7")
        validator.save(ctx2, listOf(fresh))

        val mrLevel = persistence
            .loadFindingsCache(SnapshotManager.mrKey(mr))
            .values
            .flatten()
            .filter { it.location !is Location.FileRef }
        assertEquals(setOf(fresh, other), mrLevel.toSet())
    }

    @Test
    fun lastReviewedShaIsSavedAndLoadedOnTheNextRun() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val first = makeMr(changes = listOf(makeChange("src/Foo.kt", "a"))).copy(diffRefs = DiffRefs("base", "head1"))
        val ctx1 = validator.prepare(first)
        assertNull(ctx1.lastReviewedSha)
        assertEquals("head1", ctx1.headSha)
        validator.save(ctx1, emptyList())

        val history = listOf("head1", "head2").map { Commit(it, "m", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z") }
        val second = makeMr(changes = listOf(makeChange("src/Foo.kt", "a"), makeChange("src/Bar.kt")), commits = history)
            .copy(diffRefs = DiffRefs("base", "head2"))
        val ctx2 = validator.prepare(second)

        assertEquals("head1", ctx2.lastReviewedSha)
        assertEquals(listOf("src/Foo.kt"), ctx2.unchangedFiles)
        validator.save(ctx2, emptyList())
        assertEquals("head2", validator.prepare(second).lastReviewedSha)
    }

    // A prior finding the model was told not to repeat stays in the cache for the next prompt

    private fun diffAdding(vararg lines: String) = "@@ -1,1 +1,${lines.size + 1} @@\n ctx\n" + lines.joinToString("\n") { "+$it" }

    private fun anchoredFinding(
        diff: String,
        line: Int,
    ): Finding {
        val raw = Finding(Severity.WARNING, "Token read is unchecked", "ai-review", Location.FileRef("src/A.kt", line))
        return AnchorFingerprint.stamp(listOf(raw), listOf(makeChange("src/A.kt", diff))).single()
    }

    @Test
    fun `prior finding not repeated in run 2 is still prior context in run 3`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val diff1 = diffAdding("val token = read()")
        val prior = anchoredFinding(diff1, line = 2)
        validator.save(validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff1)))), listOf(prior))

        val diff2 = diffAdding("val other = 1", "val token = read()")
        val ctx2 = validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff2))))
        assertEquals(listOf(prior), ctx2.priorFindingsByChangedFile["src/A.kt"])
        validator.save(ctx2, emptyList())

        val diff3 = diffAdding("val other = 2", "val token = read()")
        val ctx3 = validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff3))))
        assertEquals(listOf(prior.copy(location = Location.FileRef("src/A.kt", 3))), ctx3.priorFindingsByChangedFile["src/A.kt"])
    }

    @Test
    fun `prior finding whose anchored line is gone is not carried`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val diff1 = diffAdding("val token = read()", "val keep = 1")
        val gone = anchoredFinding(diff1, line = 2)
        val kept = anchoredFinding(diff1, line = 3)
        validator.save(validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff1)))), listOf(gone, kept))

        val diff2 = diffAdding("val keep = 1")
        validator.save(validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff2)))), emptyList())

        val ctx3 = validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diffAdding("val x = 0", "val keep = 1")))))
        assertEquals(listOf(kept.copy(location = Location.FileRef("src/A.kt", 2))), ctx3.priorFindingsByChangedFile["src/A.kt"])
    }

    @Test
    fun `prior finding is not carried across a config change`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val diff1 = diffAdding("val token = read()")
        val mr1 = makeMr(changes = listOf(makeChange("src/A.kt", diff1)))
        validator.save(validator.prepare(mr1, configHash = 1L), listOf(anchoredFinding(diff1, line = 2)))

        validator.save(validator.prepare(mr1, configHash = 2L), emptyList())

        val ctx3 = validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diffAdding("val token = read()", "x()")))), 2L)
        assertNull(ctx3.priorFindingsByChangedFile["src/A.kt"])
    }

    @Test
    fun `prior analyzer finding without the ai prefix is carried and a deterministic one is not`() {
        val validator = IncrementalValidator(makePersistence(), noopLogger)
        val diff1 = diffAdding("val token = read()", "val keep = 1")
        val provenance = mapOf(AiFindingMetadataKeys.AI_PROVIDER to "openai")
        val raw = listOf(
            Finding(Severity.WARNING, "Token read is unchecked", "review-comments", Location.FileRef("src/A.kt", 2), metadata = provenance),
            Finding(Severity.WARNING, "Keep is unused", "file-size", Location.FileRef("src/A.kt", 3)),
        )
        val (analyzer, deterministic) = AnchorFingerprint.stamp(raw, listOf(makeChange("src/A.kt", diff1)))
        validator.save(validator.prepare(makeMr(changes = listOf(makeChange("src/A.kt", diff1)))), listOf(analyzer, deterministic))

        val ctx2 = validator.prepare(
            makeMr(changes = listOf(makeChange("src/A.kt", diffAdding("x()", "val token = read()", "val keep = 1"))))
        )
        validator.save(ctx2, emptyList())

        val ctx3 = validator.prepare(
            makeMr(changes = listOf(makeChange("src/A.kt", diffAdding("y()", "val token = read()", "val keep = 1"))))
        )
        assertEquals(listOf(analyzer.copy(location = Location.FileRef("src/A.kt", 3))), ctx3.priorFindingsByChangedFile["src/A.kt"])
    }
}
