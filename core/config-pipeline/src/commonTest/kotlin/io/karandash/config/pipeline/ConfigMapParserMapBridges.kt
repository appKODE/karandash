package io.karandash.config.pipeline

import io.karandash.api.support.mapToJsonElement
import io.karandash.interpolation.EnvDeclaration
import io.karandash.manifest.ConfigSource

// Map-literal test inputs for the JsonElement-only parser API.

internal fun ConfigMapParser.parseFullConfig(
    data: Map<String, Any?>,
    defaults: Map<String, String> = emptyMap(),
    aiSubRuleKeys: Map<String, String> = emptyMap(),
): ConfigParseResult = parseFullConfig(mapToJsonElement(data), defaults, aiSubRuleKeys)

internal fun ConfigMapParser.parseBootstrapConfig(data: Map<String, Any?>): BootstrapConfig = parseBootstrapConfig(mapToJsonElement(data))

internal fun ConfigMapParser.parseEnvBlock(data: Map<String, Any?>): EnvDeclaration = parseEnvBlock(mapToJsonElement(data))

internal fun ConfigMapParser.getExtendsSources(data: Map<String, Any?>): List<ConfigSource> = getExtendsSources(mapToJsonElement(data))
