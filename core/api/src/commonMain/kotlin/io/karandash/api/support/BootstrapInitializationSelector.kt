package io.karandash.api.support

import io.karandash.api.Module
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule

/**
 * Returns true when a module must be initialized during CLI bootstrap.
 *
 * Bootstrap should eagerly initialize only:
 * - [ConfigFormatModule] modules (needed for config parsing/discovery)
 * - [CliCommandModule] modules (needed for command registration)
 *
 * All other modules should be registered and initialized later when selected
 * by runtime config/flow.
 */
public fun Module.shouldInitializeDuringBootstrap(): Boolean = this is ConfigFormatModule || this is CliCommandModule
