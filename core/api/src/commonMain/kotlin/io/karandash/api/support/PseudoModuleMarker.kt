package io.karandash.api.support

import io.karandash.api.Module

/**
 * Marker interface for pseudo-modules that occupy config keys but are not
 * standard rule/reporter/provider modules.
 *
 * `MissingModuleDetector` queries this interface to avoid hardcoding pseudo-module IDs.
 */
public interface PseudoModuleMarker : Module {
    /**
     * Config keys that this pseudo-module claims.
     *
     * These keys are recognized in the rules/reporters/providers sections of config
     * and should not trigger "unknown module" warnings.
     */
    public fun pseudoConfigKeys(): List<String>
}
