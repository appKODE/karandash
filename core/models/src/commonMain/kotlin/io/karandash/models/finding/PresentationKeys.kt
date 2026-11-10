package io.karandash.models.finding

/**
 * Finding metadata keys that tell formats how to present a finding, so a plugin can
 * route its output into a report section without editing any formatter.
 */
object PresentationKeys {
    /**
     * Name of the report section a finding belongs to. Formats render findings carrying it
     * in a section titled by this value, unless a legacy `is*` flag already routes them.
     */
    const val SECTION = "presentation.section"

    /** Heading shown for the finding's [SECTION] instead of the section name. */
    const val SECTION_TITLE = "presentation.sectionTitle"

    /**
     * Integer position of the finding's [SECTION] among sections: lower renders first. Sections
     * without a valid order follow the ordered ones, in order of first appearance.
     */
    const val SECTION_ORDER = "presentation.sectionOrder"

    /**
     * Groups the [SECTION]-routed [findings] by section, sorted by [SECTION_ORDER], as
     * (heading, findings) pairs. The heading is the first [SECTION_TITLE] found in the section,
     * else the section name; the order is the lowest [SECTION_ORDER] in the section.
     */
    fun sections(findings: List<Finding>): List<Pair<String, List<Finding>>> =
        findings
            .filter { it.metadata[SECTION] != null }
            .groupBy { checkNotNull(it.metadata[SECTION]) }
            .entries
            .sortedBy { (_, group) -> group.mapNotNull { it.metadata[SECTION_ORDER]?.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE }
            .map { (section, group) -> (group.firstNotNullOfOrNull { it.metadata[SECTION_TITLE] } ?: section) to group }
}
