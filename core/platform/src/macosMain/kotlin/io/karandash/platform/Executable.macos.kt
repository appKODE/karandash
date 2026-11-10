package io.karandash.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutable(): String? = NSBundle.mainBundle.executablePath
