# JPackage Distribution

## Overview

The JPackage distribution provides professional platform-specific installers that bundle a custom JRE with Karandash. Users can install Karandash without needing to install Java separately.

## Features

- **No Java Installation Required**: Each installer includes a custom JRE (~51MB)
- **Native Installers**: DMG for macOS, DEB/RPM for Linux, MSI for Windows
- **Professional Experience**: Standard installation flow for each platform
- **Reduced Size**: Custom JRE is 51MB vs ~180MB full JRE
- **Total Size**: ~80MB installer (vs 45MB standalone JAR)

## Building Installers

### Prerequisites

- JDK 14+ (JDK 22 recommended for optimal size)
- Platform-specific tools:
  - macOS: No additional tools needed
  - Linux: `rpm` package for building RPM files
  - Windows: WiX Toolset for MSI creation

### Build Commands

```bash
# Build custom JRE (required first)
./gradlew buildCustomJre

# Build platform-specific installer
./gradlew packageMacOS    # macOS DMG
./gradlew packageLinux    # Linux DEB and RPM
./gradlew packageWindows  # Windows MSI
```

### Output

Installers are created in `build/installers/`:
- **macOS**: `Karandash-1.2.0.dmg` (~80MB)
- **Linux**: `karandash_1.2.0_amd64.deb` (~75MB), `karandash-1.2.0.rpm` (~75MB)
- **Windows**: `Karandash-1.2.0.msi` (~85MB)

Note: Version 0.1.0 is transformed to 1.2.0 for jpackage compatibility (which doesn't allow versions starting with 0).

## Installation

### macOS
1. Download `Karandash-1.2.0.dmg`
2. Open the DMG file
3. Drag Karandash to Applications folder
4. Run from Applications or terminal: `karandash --version`

### Linux (Debian/Ubuntu)
```bash
sudo dpkg -i karandash_1.2.0_amd64.deb
karandash --version
```

### Linux (RHEL/Fedora/CentOS)
```bash
sudo rpm -i karandash-1.2.0.rpm
karandash --version
```

### Windows
1. Download `Karandash-1.2.0.msi`
2. Double-click to run installer
3. Follow installation wizard
4. Run from Start Menu or terminal: `karandash --version`

## Custom JRE Modules

The custom JRE includes only the modules required by Karandash:
- `java.base` - Core Java functionality
- `java.desktop` - AWT/Swing (for jpackage)
- `java.instrument` - Java agent support
- `java.security.sasl` - SASL authentication
- `java.sql` - Database connectivity
- `jdk.management` - JMX management
- `jdk.unsupported` - sun.misc.Unsafe (used by some libraries)

## CI/CD Integration

GitHub Actions workflow automatically builds all installers on:
- Tag pushes matching `v*` pattern
- Manual workflow dispatch

Artifacts are uploaded to GitHub Releases.

## Technical Details

### JLink Configuration
- Compression level: 2 (maximum)
- Strip debug symbols: Yes
- Strip header files: Yes
- Strip man pages: Yes

### JPackage Options
- Vendor: Karandash
- Copyright: Copyright 2024 Karandash
- Description: Merge Request Validation Tool
- Main class: `io.karandash.cli.MainKt`

### Platform-Specific Options

**macOS**:
- Package name: Karandash
- Bundle identifier: `io.karandash.cli`

**Linux**:
- Package name: karandash
- Category: utils
- Creates desktop shortcut

**Windows**:
- Allows directory chooser during installation
- Creates Start Menu entry
- Creates desktop shortcut

## Comparison with Other Distributions

| Feature | JPackage | Shadow JAR | Hybrid | Native Image |
|---------|----------|------------|--------|--------------|
| Size | 75-85 MB | 45 MB | 28.5 MB | 18-25 MB |
| Startup | 2-3s | 2-3s | 2-3s | 50-80ms |
| Java Required | No | Yes | Yes | No |
| Professional Installer | ✓ | ✗ | ✗ | ✗ |
| All Plugins | ✓ | ✓ | ✓ | ✗ |

## Troubleshooting

### Build Failures

**"jpackage not found"**:
- Make sure JDK 14+ is installed (not just JRE)
- Verify `JAVA_HOME` points to JDK

**"directory already exists"**:
- Clean build directory: `rm -rf build/custom-jre build/installers`

**"Bundler Mac DMG Package skipped"** (Linux/Windows only):
- This is expected - installers must be built on their target platform

### Installation Issues

**macOS: "Karandash.app is damaged and can't be opened"**:
- Run: `xattr -cr /Applications/Karandash.app`
- This removes quarantine attribute from unsigned app

**Linux: "karandash: command not found"** after installation:
- Check installation path: `dpkg -L karandash` or `rpm -ql karandash`
- Add to PATH if needed

**Windows: "This app has been blocked for your protection"**:
- Click "More info" → "Run anyway"
- Or sign the installer with a code signing certificate

## Not Supported

Installers are unsigned, there is no auto-update, no native splash screen and no file association for `.karandash/config.yaml`.
