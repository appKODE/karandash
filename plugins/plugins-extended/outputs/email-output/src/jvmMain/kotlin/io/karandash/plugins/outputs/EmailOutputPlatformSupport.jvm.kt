package io.karandash.plugins.outputs

actual object EmailOutputPlatformSupport {
    actual fun isEmailSendingSupported(): Boolean = true
}
