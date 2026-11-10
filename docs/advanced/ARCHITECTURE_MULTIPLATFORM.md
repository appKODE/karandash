# Multiplatform Architecture

Karandash runs the same validation code on the JVM and on Kotlin/Native (macOS, Linux, Windows). Shared code lives in `commonMain`. Anything that touches the OS goes through a small `expect`/`actual` abstraction.

```
Application (CLI, validators)
        |
Common code (commonMain): Path extensions, PlatformFS, PlatformEnvironment, PlatformProcess
        |
jvmMain | macosArm64 / macosX64 | linuxX64 | mingwX64   (actuals)
```

## File I/O

All file access uses `kotlinx.io.files.Path` and `SystemFileSystem`. It works the same on every target, needs no reflection and gives buffered I/O.

`core/file` adds extension functions on `Path`: `exists()`, `isFile()`, `isDirectory()`, `readText()`, `writeText()`, `toAbsolutePath()`, `listDirectory()`. All except `lastModifiedMillis()` are plain common code. `lastModifiedMillis()` is `expect`, because kotlinx-io has no modification time:

- JVM: `java.io.File.lastModified()`.
- macOS: POSIX `stat()`, `st_mtimespec`.
- Linux: POSIX `stat()`, `st_mtime`.
- Windows: not implemented, returns `0`.

## Directories

`PlatformFS` (`core/file`) gives the working directory, the home directory and a readability check. Each has a small `expect` function that returns a string. The temp directory comes from kotlinx-io (`SystemTemporaryDirectory`).

## Console input

There is no platform class. Interactive prompts use Mordant's `terminal.prompt(...)` and plain line input uses `readlnOrNull()`. Both work on every target.

## File watching

`PlatformFileWatcher` (`core/watcher`) backs `--watch`. It has `startWatching(onChange)`, `stop()` and `isSupported()`.

- JVM: `java.nio.file.WatchService`.
- macOS: kqueue through cinterop.
- Linux: inotify through cinterop.
- Windows: `isSupported()` returns `false`.

## Environment and processes

`PlatformEnvironment` (`core/platform`) reads environment variables (`getEnv`, `getAllEnv`) and writes to stderr. It uses `System.getenv()` on the JVM and `getenv()` from `platform.posix` on native.

`PlatformProcess` runs an external command and returns the exit code, stdout and stderr. It uses `ProcessBuilder` on the JVM and POSIX process calls on native (Windows process APIs on mingw).

## Dependencies

Common code uses kotlinx-io, kotlinx-serialization and kotlinx-datetime. Native targets link against system libraries only: Foundation and CoreServices on macOS, glibc on Linux, the Windows API on mingw.

## Tests

Tests go in `commonTest` by default. It runs on the JVM and on every native target the module declares. `jvmTest` holds JVM-only cases (mockk, JDK APIs). `nativeTest` holds POSIX and `dlopen` behaviour. Most coverage of the abstractions comes from the modules that use them:

| Area | Tests |
|------|-------|
| File I/O | `plugins/loader/src/commonTest/kotlin/io/karandash/loader/FileIOExtensionsTest.kt` |
| Subprocesses | `core/platform/src/nativeTest/kotlin/io/karandash/platform/PlatformProcessTimeoutTest.kt`, `core/platform/src/commonTest/kotlin/io/karandash/platform/GitArgSanitizerTest.kt` |
| Dynamic libraries | `core/platform/src/nativeTest/kotlin/io/karandash/platform/NativeDynamicLibraryTest.kt`, `plugins/loader/src/nativeTest/` |
| File watching | `core/watcher/src/jvmTest/kotlin/io/karandash/watcher/ConfigWatcherTest.kt`, `core/watcher/src/nativeTest/kotlin/io/karandash/watcher/NativeConfigWatcherTest.kt` |
| Environment | `core/config-pipeline/src/commonTest/` (`ConfigMapParserInterpolationTest`, `CustomEnvVarsIntegrationTest`) |

The scripts in `tools/integration-tests/` test the built binaries end to end. `run-tests.sh` drives both the JAR and the host `cli.kexe`. `feature-e2e.sh` runs the [feature matrix](../development/feature-matrix.md) scenarios against a mock GitLab server.

```bash
./gradlew jvmTest                 # JVM, all modules
./gradlew macosArm64Test          # native, macOS ARM64 (linuxX64Test on Linux)
bash tools/integration-tests/run-tests.sh   # needs :cli:shadowJar and :cli:linkReleaseExecutable<Host>
```

The pre-merge gate (`tools/ci/gate.sh`) runs all three.

See also [Building the Native CLI](BUILD_NATIVE_CLI.md) and [Plugin System](MULTIPLATFORM_PLUGIN_SYSTEM.md).
