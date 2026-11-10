#!/usr/bin/env bash
# karandash.sh - Local Docker-based build, test, and benchmark runner
#
# Usage:
#   ./docker/karandash.sh test                    # Run jvmTest + ktlint + detekt
#   ./docker/karandash.sh test-class <pattern> [task] [extra-gradle-args...]
#                                              # Run only tests matching class pattern
#   ./docker/karandash.sh build jar               # Build thin JAR
#   ./docker/karandash.sh build fatjar            # Build fat JAR (shadowJar)
#   ./docker/karandash.sh build native            # Build native Linux x64 binary
#   ./docker/karandash.sh build all               # Build all artifacts
#   ./docker/karandash.sh benchmark [iterations]  # Run benchmarks (default: 30)
#   ./docker/karandash.sh competitor-bench [iter]  # Run competitor benchmarks
#   ./docker/karandash.sh plugin-tests            # Run plugin integration tests
#   ./docker/karandash.sh package jvm             # Build runtime-jvm image
#   ./docker/karandash.sh package native          # Build runtime-native image
#   ./docker/karandash.sh images build            # Build all toolchain images
#   ./docker/karandash.sh images push <registry>  # Tag + push to remote registry
#   ./docker/karandash.sh login                   # Docker login (Docker Hub)
#
# Push to Docker Hub:
#   ./docker/karandash.sh login
#   ./docker/karandash.sh images build
#   ./docker/karandash.sh images push docker.io/${DOCKER_HUB_USERNAME:-rinekri}
#
# Environment:
#   KARANDASH_REGISTRY         - Local image prefix (default: karandash)
#   KARANDASH_REMOTE_REGISTRY  - Remote registry for push (overrides CLI arg)
#   KARANDASH_TAG              - Image tag (default: latest)
#   BENCH_ITERATIONS           - Benchmark iterations (default: 30)
#   GRADLE_OPTS                - Override Gradle JVM args

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_DIR="${PROJECT_DIR}/build/docker-out"

REGISTRY="${KARANDASH_REGISTRY:-karandash}"
BASE_TAG="${KARANDASH_TAG:-latest}"
ITERATIONS="${BENCH_ITERATIONS:-30}"

# Auto-detect host architecture for native Docker execution (avoids QEMU)
HOST_ARCH="$(uname -m)"
if [[ "$HOST_ARCH" == "arm64" || "$HOST_ARCH" == "aarch64" ]]; then
    PLATFORM="${KARANDASH_PLATFORM:-linux/arm64}"
else
    PLATFORM="${KARANDASH_PLATFORM:-linux/amd64}"
fi

# Platform-specific tag to avoid image conflicts between arm64 and amd64
PLATFORM_SUFFIX="${PLATFORM##*/}"
TAG="${BASE_TAG}-${PLATFORM_SUFFIX}"

# Gradle cache volume (persisted across runs for speed)
GRADLE_CACHE_VOL="karandash-gradle-cache"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}[karandash]${NC} $*"; }
ok()    { echo -e "${GREEN}[karandash]${NC} $*"; }
warn()  { echo -e "${YELLOW}[karandash]${NC} $*"; }
err()   { echo -e "${RED}[karandash]${NC} $*" >&2; }

cleanup_dangling_karandash_images() {
    local titles=(
        "karandash-base"
        "karandash-toolchain"
        "karandash-benchmark-env"
        "karandash-competitor-env"
        "karandash"
        "karandash-native"
    )

    for title in "${titles[@]}"; do
        docker image prune -f \
            --filter "dangling=true" \
            --filter "label=org.opencontainers.image.title=${title}" >/dev/null || true
    done
}

ensure_image() {
    local image="$1"
    local dockerfile="$2"
    local build_args="${3:-}"

    if ! docker image inspect "${image}:${TAG}" &>/dev/null; then
        info "Building ${image}:${TAG}..."
        local cmd="docker buildx build --platform ${PLATFORM} --provenance=false --sbom=false -f ${SCRIPT_DIR}/${dockerfile} -t ${image}:${TAG} --load"
        if [ -n "$build_args" ]; then
            cmd="$cmd $build_args"
        fi
        eval "$cmd ${PROJECT_DIR}"
    fi
}

run_in_base() {
    ensure_image "${REGISTRY}/base" "Dockerfile.base"

    docker run --rm \
        --platform "${PLATFORM}" \
        -v "${PROJECT_DIR}:/build" \
        -v "${GRADLE_CACHE_VOL}:/root/.gradle" \
        -w /build \
        -e GRADLE_OPTS="${GRADLE_OPTS:--Xmx4g -XX:MaxMetaspaceSize=1g}" \
        "${REGISTRY}/base:${TAG}" \
        "$@"
}

run_in_toolchain() {
    ensure_image "${REGISTRY}/base" "Dockerfile.base"
    ensure_image "${REGISTRY}/toolchain" "Dockerfile.toolchain" "--build-arg BASE_IMAGE=${REGISTRY}/base:${TAG}"

    docker run --rm \
        --platform "${PLATFORM}" \
        -v "${PROJECT_DIR}:/build" \
        -v "${GRADLE_CACHE_VOL}:/root/.gradle" \
        -w /build \
        -e GRADLE_OPTS="${GRADLE_OPTS:--Xmx4g -XX:MaxMetaspaceSize=1g}" \
        "${REGISTRY}/toolchain:${TAG}" \
        "$@"
}

cmd_test() {
    info "Running tests + lint..."
    run_in_base ./gradlew jvmTest ktlintCheck detekt --no-daemon
    ok "Tests passed."
}

cmd_test_class() {
    local class_pattern="${1:-}"
    local task="${2:-linuxX64Test}"

    if [ -z "$class_pattern" ]; then
        err "Missing test class pattern. Usage:"
        err "  ./docker/karandash.sh test-class '<class-or-pattern>' [task] [extra-gradle-args...]"
        err "Example:"
        err "  ./docker/karandash.sh test-class 'io.karandash.validators.MapsConfigE2ETest' :projects:navigator:linuxX64Test"
        exit 1
    fi

    local extra_args=()
    if [ "$#" -gt 2 ]; then
        extra_args=("${@:3}")
    fi

    info "Running ${task} with --tests '${class_pattern}'..."
    run_in_base ./gradlew "${task}" --tests "${class_pattern}" --no-daemon ${extra_args[@]+"${extra_args[@]}"}
    ok "Class-scoped tests passed."
}

cmd_build() {
    local target="${1:-fatjar}"
    mkdir -p "${OUTPUT_DIR}"

    case "$target" in
        jar)
            info "Building thin JAR..."
            run_in_base ./gradlew :cli:jvmJar --no-daemon -x jvmTest
            cp "${PROJECT_DIR}/cli/build/libs/"cli-jvm-*.jar "${OUTPUT_DIR}/" 2>/dev/null || true
            ok "JAR → ${OUTPUT_DIR}/"
            ;;
        fatjar)
            info "Building fat JAR (shadowJar)..."
            run_in_base ./gradlew :cli:shadowJar --no-daemon -x jvmTest
            cp "${PROJECT_DIR}/cli/build/libs/karandash.jar" "${OUTPUT_DIR}/"
            ok "Fat JAR → ${OUTPUT_DIR}/karandash.jar"
            ;;
        native)
            if [[ "$PLATFORM" == "linux/arm64" ]]; then
                warn "Native binary cannot be built on linux/arm64 (no K/N compiler prebuilt for this host)"
                warn "linuxArm64 target is configured — cross-compile from x64 or macOS, or set KARANDASH_PLATFORM=linux/amd64"
            else
                info "Building native Linux x64 binary..."
                run_in_base ./gradlew :cli:linkReleaseExecutableLinuxX64 --no-daemon -x linuxX64Test
                cp "${PROJECT_DIR}/cli/build/bin/linuxX64/releaseExecutable/cli.kexe" "${OUTPUT_DIR}/"
                ok "Native binary → ${OUTPUT_DIR}/cli.kexe"
            fi
            ;;
        all)
            cmd_build fatjar
            cmd_build native
            cmd_build jar
            ;;
        *)
            err "Unknown build target: $target (use: jar, fatjar, native, all)"
            exit 1
            ;;
    esac
}

cmd_benchmark() {
    local iters="${1:-$ITERATIONS}"

    # Build artifacts first if not present
    if [ ! -f "${OUTPUT_DIR}/karandash.jar" ]; then
        cmd_build fatjar
    fi
    if [ ! -f "${OUTPUT_DIR}/cli.kexe" ]; then
        cmd_build native
    fi

    ensure_image "${REGISTRY}/benchmark-env" "Dockerfile.benchmark-env"

    info "Running benchmarks (${iters} iterations)..."
    docker run --rm \
        --platform "${PLATFORM}" \
        -v "${PROJECT_DIR}:/workspace" \
        -w /workspace \
        -e BENCH_ITERATIONS="${iters}" \
        "${REGISTRY}/benchmark-env:${TAG}" \
        bash -c "
            chmod +x build/docker-out/cli.kexe && \
            echo '--- Native binary ---' && \
            bash tools/benchmarks/benchmark-cli.sh build/docker-out/cli.kexe ${iters} native && \
            echo '' && \
            echo '--- Fat JAR ---' && \
            bash tools/benchmarks/benchmark-cli.sh 'java -jar build/docker-out/karandash.jar' ${iters} fatjar && \
            echo '' && \
            echo 'Results:' && \
            cat benchmarks/results/native.csv 2>/dev/null && \
            cat benchmarks/results/fatjar.csv 2>/dev/null
        "
    ok "Benchmarks complete."
}

cmd_competitor_bench() {
    local iters="${1:-$ITERATIONS}"

    if [ ! -f "${OUTPUT_DIR}/karandash.jar" ]; then
        cmd_build fatjar
    fi
    if [ ! -f "${OUTPUT_DIR}/cli.kexe" ]; then
        cmd_build native
    fi

    ensure_image "${REGISTRY}/benchmark-env" "Dockerfile.benchmark-env"
    ensure_image "${REGISTRY}/competitor-env" "Dockerfile.competitor-env" "--build-arg BASE_IMAGE=${REGISTRY}/benchmark-env:${TAG}"

    info "Running competitor benchmarks (${iters} iterations)..."
    docker run --rm \
        --platform "${PLATFORM}" \
        -v "${PROJECT_DIR}:/workspace" \
        -w /workspace \
        -e BENCH_ITERATIONS="${iters}" \
        "${REGISTRY}/competitor-env:${TAG}" \
        bash -c "
            chmod +x build/docker-out/cli.kexe && \
            bash tools/benchmarks/benchmark-competitors.sh
        "
    ok "Competitor benchmarks complete."
}

cmd_plugin_tests() {
    # Always rebuild to avoid stale artifacts from a previous build
    cmd_build fatjar

    # Build native binary (optional - may fail on emulated platforms or low memory)
    if [[ "$PLATFORM" != "linux/arm64" ]]; then
        cmd_build native || warn "Native binary build failed — native runtime tests will be skipped."
    fi

    local has_native=false
    if [[ "$PLATFORM" == "linux/arm64" ]]; then
        warn "Native binary not usable on arm64 — native runtime tests will be skipped."
    elif [ -f "${OUTPUT_DIR}/cli.kexe" ]; then
        has_native=true
    else
        warn "Native binary not available — native runtime tests will be skipped."
    fi

    local native_cp="true"
    local native_env=""
    if [ "$has_native" = true ]; then
        native_cp="cp build/docker-out/cli.kexe /tmp/karandash-native && chmod +x /tmp/karandash-native"
        native_env="KARANDASH_NATIVE=/tmp/karandash-native"
    fi

    run_in_toolchain bash -c "
        cp build/docker-out/karandash.jar /tmp/karandash.jar && \
        ${native_cp} && \
        KARANDASH_JAR=/tmp/karandash.jar \
        ${native_env} \
        FIXTURES_DIR=/build/tools/integration-tests/fixtures \
        MOCK_SERVER=/build/tools/integration-tests/mock-server/mock-gitlab-api.py \
        TEST_MR=/build/tools/integration-tests/fixtures/test-mr.json \
        bash tools/integration-tests/plugin-examples-test.sh tools/examples
    "
    ok "Plugin integration tests complete."
}

cmd_package() {
    local target="${1:-jvm}"

    case "$target" in
        jvm)
            if [ ! -f "${OUTPUT_DIR}/karandash.jar" ]; then
                cmd_build fatjar
            fi
            info "Building runtime-jvm image..."
            docker buildx build --platform "${PLATFORM}" \
                --provenance=false --sbom=false \
                -f "${SCRIPT_DIR}/Dockerfile.runtime-jvm" \
                --build-arg JAR_FILE=build/docker-out/karandash.jar \
                -t "${REGISTRY}/runtime-jvm:${TAG}" \
                --load "${PROJECT_DIR}"
            ok "Image: ${REGISTRY}/runtime-jvm:${TAG}"
            ;;
        native)
            if [ ! -f "${OUTPUT_DIR}/cli.kexe" ]; then
                cmd_build native
            fi
            info "Building runtime-native image..."
            docker buildx build --platform "${PLATFORM}" \
                --provenance=false --sbom=false \
                -f "${SCRIPT_DIR}/Dockerfile.runtime-native" \
                --build-arg BINARY_FILE=build/docker-out/cli.kexe \
                -t "${REGISTRY}/runtime-native:${TAG}" \
                --load "${PROJECT_DIR}"
            ok "Image: ${REGISTRY}/runtime-native:${TAG}"
            ;;
        *)
            err "Unknown package target: $target (use: jvm, native)"
            exit 1
            ;;
    esac
}

cmd_images_build() {
    info "Building all toolchain images..."
    docker buildx build --platform "${PLATFORM}" \
        --provenance=false --sbom=false \
        -f "${SCRIPT_DIR}/Dockerfile.base" \
        -t "${REGISTRY}/base:${TAG}" \
        --load "${PROJECT_DIR}"
    ok "Built ${REGISTRY}/base:${TAG}"

    docker buildx build --platform "${PLATFORM}" \
        --provenance=false --sbom=false \
        -f "${SCRIPT_DIR}/Dockerfile.toolchain" \
        --build-arg BASE_IMAGE="${REGISTRY}/base:${TAG}" \
        -t "${REGISTRY}/toolchain:${TAG}" \
        --load "${PROJECT_DIR}"
    ok "Built ${REGISTRY}/toolchain:${TAG}"

    docker buildx build --platform "${PLATFORM}" \
        --provenance=false --sbom=false \
        -f "${SCRIPT_DIR}/Dockerfile.benchmark-env" \
        -t "${REGISTRY}/benchmark-env:${TAG}" \
        --load "${PROJECT_DIR}"
    ok "Built ${REGISTRY}/benchmark-env:${TAG}"

    docker buildx build --platform "${PLATFORM}" \
        --provenance=false --sbom=false \
        -f "${SCRIPT_DIR}/Dockerfile.competitor-env" \
        --build-arg BASE_IMAGE="${REGISTRY}/benchmark-env:${TAG}" \
        -t "${REGISTRY}/competitor-env:${TAG}" \
        --load "${PROJECT_DIR}"
    ok "Built ${REGISTRY}/competitor-env:${TAG}"

    cleanup_dangling_karandash_images
    ok "All toolchain images built."
}

cmd_images_push() {
    local remote="${1:-${KARANDASH_REMOTE_REGISTRY:-}}"
    if [ -z "$remote" ]; then
        err "Registry not specified. Usage:"
        err "  ./docker/karandash.sh images push <registry>"
        err "  KARANDASH_REMOTE_REGISTRY=docker.io/${DOCKER_HUB_USERNAME:-rinekri} ./docker/karandash.sh images push"
        exit 1
    fi

    local images=(base toolchain benchmark-env competitor-env)

    for img in "${images[@]}"; do
        local local_tag="${REGISTRY}/${img}:${TAG}"
        local remote_tag="${remote}/${img}:${TAG}"

        if ! docker image inspect "${local_tag}" &>/dev/null; then
            warn "Local image ${local_tag} not found — skipping (run 'images build' first)"
            continue
        fi

        info "Pushing ${remote_tag}..."
        if [ "${local_tag}" != "${remote_tag}" ]; then
            docker tag "${local_tag}" "${remote_tag}"
        fi
        docker push "${remote_tag}"
    done

    # Push runtime images if they exist
    for img in runtime-jvm runtime-native; do
        local local_tag="${REGISTRY}/${img}:${TAG}"
        local remote_tag="${remote}/${img}:${TAG}"

        if docker image inspect "${local_tag}" &>/dev/null; then
            info "Pushing ${remote_tag}..."
            if [ "${local_tag}" != "${remote_tag}" ]; then
                docker tag "${local_tag}" "${remote_tag}"
            fi
            docker push "${remote_tag}"
        fi
    done

    cleanup_dangling_karandash_images
    ok "All images pushed to ${remote}."
}

cmd_login() {
    local registry_host="${1:-docker.io}"
    info "Logging in to ${registry_host}..."
    docker login "${registry_host}"
    ok "Logged in to ${registry_host}."
}

usage() {
    cat <<'EOF'
Usage: karandash.sh <command> [args]

Commands:
  test                          Run jvmTest + ktlint + detekt
  test-class <pattern> [task]   Run only tests matching class pattern
  build <jar|fatjar|native|all> Build artifacts (output: build/docker-out/)
  benchmark [iterations]        Run native + fatjar benchmarks
  competitor-bench [iterations] Run competitor benchmarks
  plugin-tests                  Run multi-language plugin integration tests
  package <jvm|native>          Build minimal runtime Docker image
  images build                  Build all toolchain images locally
  images push <registry>        Tag and push images to remote registry
  login [registry-host]         Docker login (default: Docker Hub)

Example workflow (push to Docker Hub):
  ./docker/karandash.sh login
  ./docker/karandash.sh images build
  ./docker/karandash.sh images push docker.io/${DOCKER_HUB_USERNAME:-rinekri}

Class-only test example:
  ./docker/karandash.sh test-class 'io.karandash.validators.MapsConfigE2ETest' :projects:navigator:linuxX64Test

Environment variables:
  KARANDASH_REGISTRY          Local image prefix (default: karandash)
  KARANDASH_REMOTE_REGISTRY   Remote registry for push (overrides push argument)
  KARANDASH_TAG               Image tag (default: latest)
  BENCH_ITERATIONS            Benchmark iterations (default: 30)
  GRADLE_OPTS                 Gradle JVM arguments
EOF
}

# Main dispatch
case "${1:-help}" in
    test)               cmd_test ;;
    test-class)         shift; cmd_test_class "$@" ;;
    build)              cmd_build "${2:-fatjar}" ;;
    benchmark)          cmd_benchmark "${2:-$ITERATIONS}" ;;
    competitor-bench)   cmd_competitor_bench "${2:-$ITERATIONS}" ;;
    plugin-tests)       cmd_plugin_tests ;;
    package)            cmd_package "${2:-jvm}" ;;
    login)              cmd_login "${2:-docker.io}" ;;
    images)
        case "${2:-build}" in
            build) cmd_images_build ;;
            push)  cmd_images_push "${3:-${KARANDASH_REMOTE_REGISTRY:-}}" ;;
            *)     err "Unknown images subcommand: $2"; exit 1 ;;
        esac
        ;;
    help|--help|-h)     usage ;;
    *)                  err "Unknown command: $1"; usage; exit 1 ;;
esac
