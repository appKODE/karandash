package io.karandash.api.support

/**
 * Default constants for reporter module selection.
 *
 * Decouples CLI code from concrete reporter module imports.
 */
public object ReporterDefaults {
    /** Default format ID used when no `--format` flag is provided. */
    public const val DEFAULT_FORMAT_ID: String = "console"
}
