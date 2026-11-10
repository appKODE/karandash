package io.karandash.bootstrap

/**
 * Returns the platform key for the current runtime environment.
 * Used for selecting platform-specific plugin artifacts from the registry.
 *
 * Possible values:
 * - "jvm" - JVM runtime (any OS)
 * - "macos-arm64" - macOS Apple Silicon
 * - "macos-x64" - macOS Intel
 * - "linux-x64" - Linux x86_64
 * - "windows-x64" - Windows x64
 */
expect fun currentPlatformKey(): String
