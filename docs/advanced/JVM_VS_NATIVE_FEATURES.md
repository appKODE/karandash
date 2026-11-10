# JVM vs Native

Karandash ships in two forms. Both run the same validators and read the same config.

| Distribution | Size | Startup | Needs Java |
|--------------|------|---------|------------|
| JVM (standard JAR or hybrid) | 45-58 MB | about 0.4 s warm, 2-3 s cold in CI | Yes (17+) |
| Native binary | 9.1 MB | 11-25 ms warm, about 0.6 s cold | No |

Memory: about 180 MB idle and 250-400 MB under load on the JVM, about 80 MB under load on native.

## What differs

| Feature | JVM | Native |
|---------|-----|--------|
| Built-in validators, all config formats (KTS, YAML, JSON), zero-config, `extends` | yes | yes |
| All platforms (GitLab, GitHub, Bitbucket, Gerrit, Azure DevOps, AWS CodeCommit, Gitea) | yes | yes (HTTP through ktor-client-curl) |
| Console, JSON, SARIF output, comment mode, `--pr`, `--fix` | yes | yes |
| `--watch` | yes (`WatchService`) | yes (kqueue on macOS, inotify on Linux), not on Windows |
| JAR plugins | loaded directly | through a JVM subprocess, needs Java 17+ on `PATH` or `JAVA_HOME` |
| Native plugins (C, C++, Rust, Kotlin/Native) | JNA | cinterop with `dlopen`/`dlsym` (`LoadLibrary` on Windows) |
| JavaScript plugins | QuickJS | QuickJS |
| WASM plugins | Chicory | Chasm (1.7.0 on the macOS x64 binary) |
| Plugin signature verification | `java.security` | OpenSSL |
| LSP server and VS Code extension | yes | no |

There is no IntelliJ plugin on either side.

YAML on native is parsed with yamlkt 0.13.0, which is alpha quality. JSON is the most stable format if you hit a parser bug.

## Config formats

Config is found automatically: `.karandash/config.kts`, `.yaml`, `.yml` or `.json`. KTS is parsed with antlr-kotlin on native and ANTLR4 on the JVM, from the same grammar. KTS adds about 1.2 MB to the native binary.

## Native plugins

Native plugins use the C ABI described in the [native plugin guide](../plugins/native-plugin-guide.md). The plugin allocates every string it returns and the host gives it back through `karandash_free_string()`. Plugins are looked up in `./plugins` and `~/.karandash/plugins`.

JAR plugins on native work like this: the binary starts a JVM subprocess that loads the JARs with `ServiceLoader` and talks to it over JSON. That is why Java 17+ must be available.

## Watch mode

`--watch` reruns validation when the config file changes. Changes are debounced for 500 ms. Ctrl+C stops it cleanly.

```bash
karandash validate --config .karandash/config.kts --watch
```

## Which one to use

Use native when startup time or size matters: CI jobs, pre-commit hooks, small containers, serverless, air-gapped machines without Java.

Use JVM if you need the LSP server or IDE integration, or you do not want Java 17+ as a dependency only for JAR plugins.

You can mix them. Develop plugins with the JVM build and run the native binary in CI.

## FAQ

**Does native support all plugin types?** Yes. JAR plugins need Java 17+ on the machine.

**Does native support Kotlin DSL configs?** Yes, since 0.7.0.

**Which platforms have a native binary?** macOS ARM64, macOS x64, Linux x64, Windows x64.
