package io.karandash.plugins.parsers.kts

import io.karandash.sdk.config.ConfigFormatModuleAdapter

/**
 * ServiceLoader entry point for the KTS config format parser.
 */
class KtsFormatModuleLoader : ConfigFormatModuleAdapter(KtsFormatModule())
