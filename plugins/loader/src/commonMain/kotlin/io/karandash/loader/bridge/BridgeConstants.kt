package io.karandash.loader.bridge

/**
 * Shared string constants for bridge transport method and property names.
 *
 * Avoids detekt `StringLiteralDuplication` violations across bridge module files.
 */
internal object BridgeConstants {
    const val PROP_ID = "id"
    const val PROP_NAME = "name"
    const val PROP_VERSION = "version"
    const val PROP_MIN_API_LEVEL = "minApiLevel"
    const val NULL_SENTINEL = "null"

    /** The guest's JSON `ModuleDescriptor`; native symbol `karandash_describe`. */
    const val PROP_DESCRIBE = "describe"

    /** Int properties a native guest answers through its single `karandash_default_priority` export. */
    val PRIORITY_PROPERTIES: Set<String> = setOf(
        "defaultPriority",
        "interceptorPriority",
        "processorPriority",
        "transformerPriority",
        "enricherPriority",
        "redactorPriority",
    )
}
