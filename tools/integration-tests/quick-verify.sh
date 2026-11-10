#!/bin/bash
#
# Quick verification script for integration test setup
# Verifies that the test infrastructure is properly configured
#

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "╔════════════════════════════════════════╗"
echo "║ Integration Tests Setup Verification   ║"
echo "╚════════════════════════════════════════╝"
echo ""

# Check 1: Verify karandash.sh exists
echo -n "Checking karandash.sh exists... "
if [ -f "$PROJECT_ROOT/karandash.sh" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 2: Verify JAR is built
echo -n "Checking karandash.jar is built... "
if [ -f "$PROJECT_ROOT/cli/build/libs/karandash.jar" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    echo "Build karandash.jar first with: ./gradlew :cli:shadowJar"
    exit 1
fi

# Check 3: Verify integration-tests directory
echo -n "Checking integration-tests directory... "
if [ -d "$PROJECT_ROOT/tools/integration-tests" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 4: Verify run-tests.sh
echo -n "Checking run-tests.sh exists... "
if [ -f "$PROJECT_ROOT/tools/integration-tests/run-tests.sh" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 5: Verify README.md
echo -n "Checking README.md exists... "
if [ -f "$PROJECT_ROOT/tools/integration-tests/README.md" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 6: Verify test data files
echo -n "Checking test data directory... "
TEST_DATA="$PROJECT_ROOT/tools/integration-tests/test-data"
if [ -f "$TEST_DATA/test-config.kts" ] && python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$TEST_DATA/sample-mr.json"; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 7: Test karandash.sh works
echo -n "Testing karandash.sh --help... "
if "$PROJECT_ROOT/karandash.sh" --help > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

# Check 8: Test plugin create command for every type
TEMP_TEST="$(mktemp -d)"
trap 'rm -rf "$TEMP_TEST"' EXIT
for type in rule reporter provider mutator comment-poster; do
    echo -n "Testing karandash plugin create --type $type... "
    if (cd "$TEMP_TEST" && "$PROJECT_ROOT/karandash.sh" plugin create "verify-$type" --type "$type" --no-git > /dev/null 2>&1) \
        && ls "$TEMP_TEST/verify-$type-plugin/src/commonMain/kotlin/"*/*/*/*Module.kt > /dev/null 2>&1; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
        exit 1
    fi
done

# Check 8b: The fat JAR prints no SLF4J "No providers" banner (an MR fetch initializes slf4j)
echo -n "Testing karandash.jar prints no SLF4J banner... "
if (cd "$TEMP_TEST" && java -jar "$PROJECT_ROOT/cli/build/libs/karandash.jar" validate --no-auto-detect \
        --mr http://127.0.0.1:9/g/p/-/merge_requests/1 2>&1 || true) | grep -q "SLF4J"; then
    echo -e "${RED}✗${NC}"
    exit 1
else
    echo -e "${GREEN}✓${NC}"
fi

# Check 9: Verify example plugins exist
echo -n "Checking example plugins exist... "
EXAMPLE_COUNT=$(find "$PROJECT_ROOT/tools/examples" -name "build.gradle.kts" | wc -l)
if [ "$EXAMPLE_COUNT" -ge 2 ]; then
    echo -e "${GREEN}✓${NC} (found $EXAMPLE_COUNT)"
else
    echo -e "${RED}✗${NC} (found $EXAMPLE_COUNT)"
    exit 1
fi

# Check 10: Verify Gradle wrapper
echo -n "Checking gradle wrapper... "
if [ -f "$PROJECT_ROOT/gradlew" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗${NC}"
    exit 1
fi

echo ""
echo "╔════════════════════════════════════════╗"
echo "║ All Setup Checks Passed!               ║"
echo "╚════════════════════════════════════════╝"
echo ""
echo "You can now run the full integration tests:"
echo "  ./tools/integration-tests/run-tests.sh"
