#!/usr/bin/env bash
set -e

# Karandash Distribution Benchmarking Script

echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║              KARANDASH DISTRIBUTION PERFORMANCE BENCHMARKS                   ║"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""

ITERATIONS="${1:-100}"
RESULTS_FILE="DISTRIBUTION_BENCHMARKS.md"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

if [ "$OS" = "darwin" ]; then
    PLATFORM="macos"
elif [ "$OS" = "linux" ]; then
    PLATFORM="linux"
else
    PLATFORM="windows"
fi

if [ "$ARCH" = "arm64" ]; then
    ARCH="arm64"
elif [ "$ARCH" = "x86_64" ]; then
    ARCH="x64"
fi

PLATFORM_FULL="${PLATFORM}-${ARCH}"

echo "Platform: $PLATFORM_FULL"
echo "Iterations: $ITERATIONS"
echo ""

# Helper function to measure startup time
measure_startup() {
    local cmd="$1"
    local name="$2"
    
    echo -n "Testing $name startup time ($ITERATIONS iterations)... " >&2
    
    local total=0
    local min=999999
    local max=0
    
    for i in $(seq 1 $ITERATIONS); do
        start=$(date +%s%N)
        eval "$cmd" > /dev/null 2>&1
        end=$(date +%s%N)
        elapsed=$((($end - $start) / 1000000))
        
        total=$(($total + $elapsed))
        if [ $elapsed -lt $min ]; then min=$elapsed; fi
        if [ $elapsed -gt $max ]; then max=$elapsed; fi
    done
    
    local avg=$(($total / $ITERATIONS))
    
    echo "Done" >&2
    echo "  Average: ${avg}ms (min: ${min}ms, max: ${max}ms)" >&2
    
    echo $avg
}

# Helper function to get file size
get_size() {
    local path="$1"
    if [ -f "$path" ]; then
        if [ "$PLATFORM" = "macos" ]; then
            stat -f%z "$path"
        else
            stat -c%s "$path"
        fi
    else
        echo "0"
    fi
}

# Format bytes
format_bytes() {
    local bytes=$1
    if [ $bytes -lt 1024 ]; then
        echo "${bytes}B"
    elif [ $bytes -lt $((1024*1024)) ]; then
        echo "$((bytes / 1024))KB"
    else
        echo "$((bytes / 1024 / 1024))MB"
    fi
}

# Results variables
jar_startup="N/A"
jar_size="0"
hybrid_startup="N/A"
hybrid_size="0"
hybrid_native_size="0"
native_startup="N/A"
native_size="0"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "1. STANDARD JAR DISTRIBUTION"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ -f "cli/build/libs/karandash.jar" ]; then
    JAR_CMD="java -jar cli/build/libs/karandash.jar --version"
    jar_startup=$(measure_startup "$JAR_CMD" "Standard JAR")
    jar_size=$(get_size "cli/build/libs/karandash.jar")
else
    echo "⚠️  Standard JAR not found. Run: ./gradlew :cli:shadowJar"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "2. HYBRID DISTRIBUTION"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

HYBRID_PATH="build/distributions/karandash-hybrid-0.1.0-$PLATFORM_FULL"
if [ -f "$HYBRID_PATH/bin/karandash" ]; then
    HYBRID_CMD="$HYBRID_PATH/bin/karandash --version"
    hybrid_startup=$(measure_startup "$HYBRID_CMD" "Hybrid")
    
    jar_part=$(get_size "$HYBRID_PATH/lib/karandash.jar")
    native_total=0
    for lib in $HYBRID_PATH/validators/*; do
        if [ -f "$lib" ]; then
            lib_size=$(get_size "$lib")
            native_total=$((native_total + lib_size))
        fi
    done
    hybrid_size=$((jar_part + native_total))
    hybrid_native_size=$native_total
else
    echo "⚠️  Hybrid distribution not found. Run: ./scripts/package-hybrid.sh 0.1.0 $PLATFORM_FULL"
fi
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "3. NATIVE IMAGE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ -f "cli/build/native/karandash" ]; then
    NATIVE_CMD="cli/build/native/karandash --version"
    native_startup=$(measure_startup "$NATIVE_CMD" "Native Image")
    native_size=$(get_size "cli/build/native/karandash")
else
    echo "⚠️  Native image not found. Run: ./gradlew :cli:nativeImage"
    echo "   (Requires GraalVM with native-image installed)"
fi
echo ""

# Generate markdown report
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "GENERATING REPORT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

cat > $RESULTS_FILE << EOF
# Karandash Distribution Performance Benchmarks

**Platform:** $PLATFORM_FULL  
**Date:** $(date +"%Y-%m-%d %H:%M:%S")  
**Iterations:** $ITERATIONS

## Summary

| Distribution | Startup Time | Binary Size | Plugin Support |
|-------------|--------------|-------------|----------------|
| **Standard JAR** | ${jar_startup}ms | $(format_bytes $jar_size) | Full (JAR/Native/JS) |
| **Hybrid** | ${hybrid_startup}ms | $(format_bytes $hybrid_size) | Full (JAR/Native/JS) |
| **Native Image** | ${native_startup}ms | $(format_bytes $native_size) | Built-in only |

## Detailed Results

### Standard JAR Distribution

- **Startup Time:** ${jar_startup}ms (average over $ITERATIONS runs)
- **Binary Size:** $(format_bytes $jar_size)
- **Plugin Support:** ✅ Full (JAR, Native, JavaScript)
- **Java Required:** ✅ Yes (17+)

**Pros:**
- Maximum flexibility
- Full plugin support
- Cross-platform (any Java 17+ runtime)

**Cons:**
- Slower startup (~2-3 seconds)
- Requires Java installation

### Hybrid Distribution

- **Startup Time:** ${hybrid_startup}ms (average over $ITERATIONS runs)
- **Total Size:** $(format_bytes $hybrid_size)
  - JAR: $(format_bytes $(($hybrid_size - $hybrid_native_size)))
  - Native Validators: $(format_bytes $hybrid_native_size)
- **Plugin Support:** ✅ Full (JAR, Native, JavaScript)
- **Java Required:** ✅ Yes (17+)

**Pros:**
- Full plugin support
- Native validators for performance
- Graceful fallback to JVM validators

**Cons:**
- Slightly larger than standard JAR
- Still requires Java installation

### Native Image Distribution

EOF

if [ "$native_startup" = "N/A" ]; then
    cat >> $RESULTS_FILE << 'EOF'
**Status:** ⚠️ Not Built

To build native image, install GraalVM and run:
```bash
./gradlew :cli:nativeImage
```

**Expected Performance:**
- Startup: 50-80ms (20-40x faster than JAR)
- Size: 18-25MB
- No plugin support (built-in validators only)
EOF
else
    speedup=$((jar_startup / native_startup))
    size_reduction=$((jar_size / native_size))
    cat >> $RESULTS_FILE << EOF
- **Startup Time:** ${native_startup}ms (${speedup}x faster than JAR)
- **Binary Size:** $(format_bytes $native_size) (${size_reduction}x smaller than JAR)
- **Plugin Support:** ❌ Built-in validators only
- **Java Required:** ❌ No

**Pros:**
- Instant startup (< 100ms)
- No Java installation required
- Perfect for CI/CD environments

**Cons:**
- No plugin support
- Per-platform compilation required
EOF
fi

cat >> $RESULTS_FILE << 'EOF'

## Recommendations

### For End Users
👉 **Hybrid Distribution** - Best balance of size, startup, and full plugin support

### For CI/CD Pipelines
👉 **Native Image** (if available) - Instant startup, no Java required

### For Maximum Flexibility
👉 **Standard JAR** - Works anywhere with Java 17+

## Testing Methodology

- Benchmarks measured on local machine
- Startup time: average of multiple iterations
- Command tested: `--version` (cold start)
- Binary sizes: on-disk file sizes
EOF

echo "✅ Report saved to: $RESULTS_FILE"
echo ""
echo "View full report:"
echo "  cat $RESULTS_FILE"
