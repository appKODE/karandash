# Multiplatform Plugin System

The plugin loader runs on the JVM and on Kotlin/Native (macOS, Linux, Windows). Shared code lives in `commonMain`. A few things are `expect`/`actual`.

For what differs between the two builds, see [JVM vs Native](JVM_VS_NATIVE_FEATURES.md).

## Layout

```
plugins/loader/src/
├── commonMain/             # shared by the JVM and all native targets
│   ├── PluginLoader.kt         # common interface
│   ├── PluginDiscovery.kt
│   ├── RemotePluginFetcher.kt  # ktor-client-core
│   ├── SignatureVerifier.kt
│   ├── KeyManager.kt
│   ├── DownloadCache.kt
│   └── ChecksumVerifier.kt
├── jvmMain/                # JNA loader for native libraries, java.nio file access
└── nativeMain/             # cinterop dlopen/dlsym loader, platform.posix file access
                            # (macosArm64, macosX64, linuxX64, mingwX64 inherit from it)
```

## Pieces worth knowing

**Files.** All code uses `kotlinx.io.files.Path` and `SystemFileSystem`. Helpers such as `exists()`, `listFiles()`, `readBytes()` and `extension()` live in `core/file`. On the JVM, `Path.toJavaFile()` and `File.toKotlinPath()` convert to `java.io.File`.

**Crypto.** `CryptoProvider` (`core/crypto`) has three functions: `sha256`, `verifyRsaSha256Signature` and `publicKeyFingerprint`. It is one common object on cryptography-kotlin (`CryptographyProvider.Default`, SHA-256 and RSA PKCS#1 v1.5), so the JVM and native share the code. It is used for plugin signatures and checksums.

**Loaders.** `PluginLoader` has `loadFromDirectories(directories, context)`, `unload(moduleId)` and `unloadAll()`. The result lists loaded, failed and skipped plugins.

| Loader | JVM | Native |
|--------|-----|--------|
| JAR | `JarPluginLoader` (`ServiceLoader`) | `JarBridgeClient` (JVM subprocess, Java 17+) |
| Native library | `NativePluginLoader` (JNA) | `NativePluginLoader` (cinterop, `dlopen`/`dlsym`) |
| JavaScript | `JsPluginLoader` (QuickJS) | `JsPluginLoader` (QuickJS) |
| WASM | `WasmPluginLoader` (Chicory) | `WasmPluginLoader` (Chasm) |

## Building the native CLI

You need a JDK 17+ for Gradle. Kotlin/Native downloads its own toolchain. No OpenSSL install is needed on any platform. Per-platform setup is in [Building the Native CLI](BUILD_NATIVE_CLI.md).

```bash
./gradlew :cli:build                       # current platform

./gradlew :cli:buildReleaseMacosArm64
./gradlew :cli:buildReleaseMacosX64
./gradlew :cli:buildReleaseLinuxX64
./gradlew :cli:buildReleaseMingwX64

ls -lh cli/build/bin/releaseExecutable/cli.kexe   # cli.exe on Windows
```

### Crypto backend

Crypto is built on cryptography-kotlin (`cryptography-provider-optimal`, with the CryptoKit provider excluded in `core/crypto/build.gradle.kts`). On native targets the provider links a prebuilt OpenSSL 3 statically. The build needs no system OpenSSL, no headers and no cinterop `.def` file.

### OpenSSL errors

If you see `Cannot find -lssl`, `openssl/evp.h: No such file or directory` or `libssl.3.dylib` not loaded, you are building from an old checkout that used a cinterop `openssl.def`. Run `./gradlew clean` and rebuild from a current checkout. Installing OpenSSL or setting library paths will not help.

## Tests

```bash
./gradlew :plugins:loader:jvmTest      # commonTest + src/jvmTest (JNA)
./gradlew :plugins:loader:nativeTest   # commonTest + src/nativeTest (file I/O, dlopen loading)
```

## WASM

JVM runs WASM plugins on Chicory. The native binary embeds Chasm 2.2.0 (pure Kotlin, no system library). The macOS x64 binary runs Chasm 1.7.0 because Chasm 2.x publishes no build for it. Its store cannot be interrupted. A call that exceeds the plugin call time limit fails and the plugin is abandoned, but its thread keeps running until exit.

## Not supported

- LSP server on native
- iOS and Android
