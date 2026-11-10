package io.karandash.plugins.outputs

import io.karandash.platform.PlatformProcess

actual object EmailOutputPlatformSupport {
    /** Native targets send through the `curl` binary ([EmailOutputClient]), so sending works only when it is on `PATH`. */
    actual fun isEmailSendingSupported(): Boolean = PlatformProcess.execute(listOf("curl", "--version")).isSuccess
}
