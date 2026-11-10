package io.karandash.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._environ

@OptIn(ExperimentalForeignApi::class)
internal actual fun environBlock(): CPointer<CPointerVar<ByteVar>>? = _environ
