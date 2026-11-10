package io.karandash.plugins.ai.statistics

import kotlinx.serialization.Serializable

/**
 * Statistics for a single validation pipeline run.
 *
 * @param timestamp Epoch seconds when this run was recorded.
 * @param mrId MR identifier, or null if unavailable.
 * @param totalFindings Total number of findings produced.
 * @param errorCount Number of ERROR-severity findings.
 * @param warningCount Number of WARNING-severity findings.
 * @param infoCount Number of INFO-severity findings.
 * @param findingsByRule Map of rule ID to finding count for that rule.
 * @param platform Id of the platform service that fetched the MR (e.g. `gitlab`), or null if unknown
 *   (local MR file, or a record written before platforms were recorded).
 * @param author Username of the MR author, or null if unknown (a record written before authors were recorded).
 */
@Serializable
data class RunRecord(
    val timestamp: Long,
    val mrId: String?,
    val totalFindings: Int,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val findingsByRule: Map<String, Int>,
    val platform: String? = null,
    val author: String? = null,
)
