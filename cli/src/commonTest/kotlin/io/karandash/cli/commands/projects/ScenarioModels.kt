package io.karandash.cli.commands.projects

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScenarioFile(
    val scenarios: List<Scenario>
)

@Serializable
data class Scenario(
    val name: String,
    val mr: ScenarioMr,
    val expect: String,
    @SerialName("expected-rules")
    val expectedRules: List<String> = emptyList(),
    @SerialName("expected-warnings")
    val expectedWarnings: List<String> = emptyList(),
)

@Serializable
data class ScenarioMr(
    val title: String,
    val description: String? = null,
    val commits: List<ScenarioCommit> = emptyList(),
    val branch: String? = null,
    val draft: Boolean = false,
    val labels: List<String> = emptyList(),
    val changes: List<ScenarioChange> = emptyList(),
)

@Serializable
data class ScenarioCommit(
    val subject: String,
    val body: String? = null,
)

@Serializable
data class ScenarioChange(
    val path: String,
    @SerialName("change-type")
    val changeType: String = "MODIFIED",
    val additions: Int = 1,
    val deletions: Int = 0,
)
