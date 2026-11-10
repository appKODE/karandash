package io.karandash.http

/** glibc `EAI_NODATA`: behind `__USE_GNU`, so platform.posix does not export it. */
internal actual val EAI_NO_ADDRESS: Int = -5
