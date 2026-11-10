package io.karandash.plugins.parsers.yaml

import io.karandash.sdk.config.ConfigFormatModuleAdapter

/**
 * ServiceLoader entry point for the YAML config format parser.
 */
class YamlFormatModuleLoader : ConfigFormatModuleAdapter(YamlFormatModule())
