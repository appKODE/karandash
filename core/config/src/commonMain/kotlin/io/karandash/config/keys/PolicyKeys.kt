package io.karandash.config.keys

import io.karandash.config.schema.ConfigSections

/** Config key constants for the policies system. */
object PolicyKeys {
    const val POLICIES = "policies"
    const val WHEN = "when"
    const val EXPRESSION = "expression"
    const val OVERRIDE = "override"
    const val ENABLED = "enabled"
    const val SEVERITY = "severity"
    const val MODULES = "modules"
    const val AUTO_APPROVE = "autoApprove"
    const val AUTO_FIX = "autoFix"
    const val SCRIPTS = ConfigSections.SCRIPTS
    const val DANGERFILE = ConfigSections.DANGERFILE
}
