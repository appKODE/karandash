package io.karandash.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.osx._NSGetEnviron

@OptIn(ExperimentalForeignApi::class)
internal actual fun environBlock(): CPointer<CPointerVar<ByteVar>>? = _NSGetEnviron()?.pointed?.value
