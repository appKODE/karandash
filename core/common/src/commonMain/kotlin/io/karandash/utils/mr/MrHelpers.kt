package io.karandash.utils.mr

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest

/**
 * Check if MR has a specific label (case-insensitive)
 */
fun MergeRequest.hasLabel(label: String): Boolean = labels.any { it.equals(label, ignoreCase = true) }

/**
 * Check if MR title matches a regex pattern
 */
fun MergeRequest.titleMatches(pattern: Regex): Boolean = pattern.containsMatchIn(title)

/**
 * Get all files matching a regex pattern
 */
fun MergeRequest.filesMatching(pattern: Regex): List<FileChange> = changes.filter { pattern.containsMatchIn(it.path) }

/**
 * Get all added files
 */
fun MergeRequest.addedFiles(): List<FileChange> = changes.filter { it.changeType == ChangeType.ADDED }

/**
 * Get all modified files
 */
fun MergeRequest.modifiedFiles(): List<FileChange> = changes.filter { it.changeType == ChangeType.MODIFIED }

/**
 * Get all deleted files
 */
fun MergeRequest.deletedFiles(): List<FileChange> = changes.filter { it.changeType == ChangeType.DELETED }
