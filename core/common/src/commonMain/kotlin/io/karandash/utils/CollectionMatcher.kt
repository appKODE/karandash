package io.karandash.utils

/**
 * Collection matching utilities for comparing lists of strings.
 *
 * Used by rule validators (labels, reviewers, approvals, file-changes) to check
 * presence/absence of required items in MR metadata.
 */
object CollectionMatcher {
    /**
     * Check if [items] contains all of [required].
     *
     * @param items The collection to search in.
     * @param required The items that must all be present.
     * @return `true` if every element of [required] is in [items].
     */
    fun hasAll(
        items: Collection<String>,
        required: List<String>,
    ): Boolean = required.all { it in items }

    /**
     * Check if [items] contains at least one of [candidates].
     *
     * @param items The collection to search in.
     * @param candidates At least one of these must be present.
     * @return `true` if any element of [candidates] is in [items].
     */
    fun hasAny(
        items: Collection<String>,
        candidates: List<String>,
    ): Boolean = candidates.any { it in items }

    /**
     * Find which of [required] are missing from [items].
     *
     * @param items The collection to search in.
     * @param required The items that should be present.
     * @return List of elements from [required] not found in [items].
     */
    fun findMissing(
        items: Collection<String>,
        required: List<String>,
    ): List<String> = required.filter { it !in items }

    /**
     * Find which of [candidates] are present in [items].
     *
     * @param items The collection to search in.
     * @param candidates The items to check for presence.
     * @return List of elements from [candidates] found in [items].
     */
    fun findPresent(
        items: Collection<String>,
        candidates: List<String>,
    ): List<String> = candidates.filter { it in items }
}
