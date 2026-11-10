package io.karandash.cli.errors

object ExitCodes {
    const val SUCCESS = 0
    const val VALIDATION_FAILED = 1
    const val CONFIG_ERROR = 2
    const val PROVIDER_ERROR = 3
    const val INTERNAL_ERROR = 4
    const val VALIDATION_WARNING = 5 // For --strict mode when warnings exist
    const val EXECUTION_ERROR = 6 // For plugin signing/compilation errors
    const val GLOBAL_TIMEOUT = 7 // Global watchdog timeout exceeded
}
