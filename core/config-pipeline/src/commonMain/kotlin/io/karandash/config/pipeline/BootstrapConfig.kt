package io.karandash.config.pipeline

import io.karandash.manifest.ExtendsEntry

/**
 * Bootstrap configuration for the first phase of two-phase config loading.
 *
 * NOTE: Intentionally retained. An earlier migration plan (plugin-based-config-formats)
 * incorrectly suggested deleting this class. It serves a distinct purpose: holding
 * [extends] chain entries needed for extends-resolution before the full config is parsed.
 * ManifestData does not carry this information. Do not remove this class.
 *
 * First pass of the two-pass config parsing:
 * 1. Parse BootstrapConfig (extends) - no env var interpolation
 * 2. Load plugins based on ManifestData
 * 3. Parse full Config with denylist-based env var interpolation
 *
 * @property extends Config inheritance sources (URLs, globs, manifests)
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `ExtendsEntry`.
 */
data class BootstrapConfig(
    val extends: List<ExtendsEntry>,
)
