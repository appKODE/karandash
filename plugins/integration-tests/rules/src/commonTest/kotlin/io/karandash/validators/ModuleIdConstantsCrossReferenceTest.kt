package io.karandash.validators

import io.karandash.profiles.ConventionalCommitsProfileModule
import io.karandash.profiles.GitFlowProfileModule
import io.karandash.profiles.GitHubFlowProfileModule
import io.karandash.profiles.MinimalProfileModule
import io.karandash.profiles.SecurityConsciousProfileModule
import io.karandash.profiles.SemanticVersioningProfileModule
import io.karandash.profiles.TrunkBasedProfileModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that every profile module's companion constant matches its actual
 * [io.karandash.api.module.ProfileModule.profileId] at runtime.
 *
 * A mismatch here means the companion constant and the module have drifted apart -
 * config lookup by profile name would silently fail at runtime.
 */
class ModuleIdConstantsCrossReferenceTest {
    @Test
    fun `MinimalProfileModule PROFILE_ID matches profileId`() {
        assertEquals(MinimalProfileModule.PROFILE_ID, MinimalProfileModule().profileId)
    }

    @Test
    fun `ConventionalCommitsProfileModule PROFILE_ID matches profileId`() {
        assertEquals(ConventionalCommitsProfileModule.PROFILE_ID, ConventionalCommitsProfileModule().profileId)
    }

    @Test
    fun `GitHubFlowProfileModule PROFILE_ID matches profileId`() {
        assertEquals(GitHubFlowProfileModule.PROFILE_ID, GitHubFlowProfileModule().profileId)
    }

    @Test
    fun `GitFlowProfileModule PROFILE_ID matches profileId`() {
        assertEquals(GitFlowProfileModule.PROFILE_ID, GitFlowProfileModule().profileId)
    }

    @Test
    fun `TrunkBasedProfileModule PROFILE_ID matches profileId`() {
        assertEquals(TrunkBasedProfileModule.PROFILE_ID, TrunkBasedProfileModule().profileId)
    }

    @Test
    fun `SemanticVersioningProfileModule PROFILE_ID matches profileId`() {
        assertEquals(SemanticVersioningProfileModule.PROFILE_ID, SemanticVersioningProfileModule().profileId)
    }

    @Test
    fun `SecurityConsciousProfileModule PROFILE_ID matches profileId`() {
        assertEquals(SecurityConsciousProfileModule.PROFILE_ID, SecurityConsciousProfileModule().profileId)
    }

    @Test
    fun `all built-in profile modules have unique profileIds`() {
        val modules = listOf(
            MinimalProfileModule(),
            ConventionalCommitsProfileModule(),
            GitHubFlowProfileModule(),
            GitFlowProfileModule(),
            TrunkBasedProfileModule(),
            SemanticVersioningProfileModule(),
            SecurityConsciousProfileModule(),
        )
        val ids = modules.map { it.profileId }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate profile module profileIds: $duplicates")
    }
}
