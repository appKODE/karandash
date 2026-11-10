package io.karandash.api.module

import io.karandash.models.finding.Finding

/**
 * Optional interface for modules that track fix outcomes.
 *
 * When auto-fix successfully applies a fix for a finding, modules implementing
 * this interface are notified so they can record the dismissal (e.g., in a
 * feedback store to prevent the finding from being regenerated on subsequent runs).
 *
 * Modules implementing this interface are discovered via [io.karandash.api.ModuleRegistry.getByType]
 * and notified by the CLI fix flow after successful fix application.
 */
public interface FixDismissalAware {
    /**
     * Called when a finding has been successfully auto-fixed.
     *
     * Implementations should record the finding as dismissed to prevent
     * regeneration on subsequent validation runs.
     *
     * @param finding The finding that was successfully fixed.
     */
    public fun onFindingFixed(finding: Finding)
}
