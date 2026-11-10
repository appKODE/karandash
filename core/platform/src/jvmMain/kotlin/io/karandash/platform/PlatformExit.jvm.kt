package io.karandash.platform

import kotlin.system.exitProcess

actual fun platformExit(status: Int): Nothing = exitProcess(status)
