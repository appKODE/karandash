package io.karandash.plugins.outputs

/** Platform guard - returns false on targets that cannot send SMTP email. */
expect object EmailOutputPlatformSupport {
    fun isEmailSendingSupported(): Boolean
}
