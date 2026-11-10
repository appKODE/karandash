package io.karandash.plugins.ai.analyzers.ownership

import kotlinx.serialization.Serializable

@Serializable
internal data class OwnershipResult(
    val ownershipScore: Double,
    val crossTeamChanges: Boolean,
    val contributors: List<ContributorInfo> = emptyList(),
    val fileOwnership: List<FileOwnershipGroup> = emptyList(),
    val bugFixCorrelations: List<BugFixCorrelation> = emptyList(),
    val knowledgeRisks: List<String> = emptyList(),
    val statistics: OwnershipStatistics,
    val summary: String,
)

@Serializable
internal data class ContributorInfo(
    val name: String,
    val linesOwned: Int = 0,
    val filesOwned: Int = 0,
    val role: String = "contributor",
)

@Serializable
internal data class FileOwnershipGroup(
    val path: String,
    val primaryOwner: String = "",
    val ownershipPercentage: Double = 0.0,
    val contributorCount: Int = 0,
)

@Serializable
internal data class BugFixCorrelation(
    val file: String,
    val bugIntroducedBy: String,
    val fixedBy: String,
    val commitMessage: String = "",
    val confidence: Double = 0.0,
)

@Serializable
internal data class OwnershipStatistics(
    val totalFilesAnalyzed: Int = 0,
    val filesWithBlameData: Int = 0,
    val uniqueCodeOwners: Int = 0,
    val uniqueFixers: Int = 0,
    val bugFixCount: Int = 0,
    val crossTeamChangeCount: Int = 0,
    val avgOwnershipScore: Double = 0.0,
)
