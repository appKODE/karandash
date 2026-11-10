# CI Integration Guide

This document describes how to integrate the Karandash integration tests into your CI/CD pipeline.

## GitHub Actions

### Basic Integration

Add the following to your GitHub Actions workflow (`.github/workflows/integration-tests.yml`):

```text
name: Integration Tests

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  integration-tests:
    runs-on: ubuntu-latest

    strategy:
      matrix:
        java-version: ['11', '17', '21']

    steps:
      - uses: actions/checkout@v3

      - name: Set up Java
        uses: actions/setup-java@v3
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'
          cache: gradle

      - name: Build Project
        run: ./gradlew :cli:shadowJar -q

      - name: Run Integration Tests
        run: LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: integration-test-results-java-${{ matrix.java-version }}
          path: build/integration-logs/
```

### Advanced Configuration

For more complex setups with multiple OS targets:

```yaml
name: Integration Tests (Multi-Platform)

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  integration-tests:
    runs-on: ${{ matrix.os }}

    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
        java-version: ['11', '17']
      fail-fast: false

    steps:
      - uses: actions/checkout@v3

      - name: Set up Java
        uses: actions/setup-java@v3
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'
          cache: gradle

      - name: Build Project
        run: ./gradlew :cli:shadowJar -q

      - name: Run Integration Tests
        run: LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

      - name: Archive Results
        if: failure()
        run: |
          tar czf integration-test-logs-${{ matrix.os }}.tar.gz build/integration-logs/
        shell: bash

      - name: Upload Failed Test Logs
        if: failure()
        uses: actions/upload-artifact@v3
        with:
          name: test-logs-${{ matrix.os }}-java-${{ matrix.java-version }}
          path: integration-test-logs-*.tar.gz
```

## GitLab CI

### Basic Integration

Add to your `.gitlab-ci.yml`:

```yaml
integration-tests:
  stage: test
  image: gradle:8.5-jdk11

  before_script:
    - apt-get update && apt-get install -y bash

  script:
    - ./gradlew :cli:shadowJar -q
    - LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

  artifacts:
    paths:
      - build/integration-logs/
    when: on_failure
    expire_in: 1 week
```

### Multi-Version Testing

```yaml
integration-tests-java-11:
  stage: test
  image: gradle:8.5-jdk11
  script:
    - ./gradlew :cli:shadowJar -q
    - LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

integration-tests-java-17:
  stage: test
  image: gradle:8.5-jdk17
  script:
    - ./gradlew :cli:shadowJar -q
    - LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

integration-tests-java-21:
  stage: test
  image: gradle:8.5-jdk21
  script:
    - ./gradlew :cli:shadowJar -q
    - LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh
```

## Jenkins

### Pipeline Script

```groovy
pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh './gradlew :cli:shadowJar -q'
            }
        }

        stage('Integration Tests') {
            steps {
                sh 'LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh'
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'build/integration-logs/**',
                            onlyIfSuccessful: false,
                            allowEmptyArchive: true

            junit testResults: 'build/integration-logs/**/*.xml',
                  allowEmptyResults: true
        }
    }
}
```

## Local Development

### Running Tests Before Commit

```bash
#!/bin/bash
# .git/hooks/pre-push

set -e

echo "Running integration tests..."
LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh

if [ $? -ne 0 ]; then
    echo "Integration tests failed!"
    exit 1
fi

echo "All integration tests passed!"
```

Make executable:

```bash
chmod +x .git/hooks/pre-push
```

### Continuous Local Testing

Using `watchman` or `fswatch`:

```bash
# Watch for changes and run tests
watchman-make -p 'src/**/*.kt' 'examples/**' 'plugins/**' -- \
    bash -c 'LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh'
```

## Expected Output

Successful test run:

```
╔════════════════════════════════════════╗
║ Karandash Integration Test Suite       ║
╚════════════════════════════════════════╝

TEST: Verifying prerequisites
✓ PASS: Java is installed
✓ PASS: Gradle wrapper found
✓ PASS: karandash.sh found
✓ PASS: Test directories created

TEST: Building karandash project
✓ PASS: Karandash project ready

================================
SCENARIO: Plugin Development Workflow
================================
TEST: Creating test integration validator plugin
✓ PASS: Plugin created successfully
...

╔════════════════════════════════════════╗
║ Test Summary                           ║
╚════════════════════════════════════════╝

Individual Test Results:
  Passed: 24
  Failed: 0

Scenario Results:
  Passed: 3
  Failed: 0

✓ All tests passed!
```

## Troubleshooting CI Failures

### "Gradle not found"

Ensure the gradle wrapper is committed:

```bash
git ls-files | grep gradle
```

### "Java version mismatch"

Check Java version in `gradle.properties` and `build.gradle.kts`:

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
```

### "Plugin build fails in CI"

Some CI environments may require explicit tmpdir configuration:

```bash
./gradlew :cli:shadowJar -Djava.io.tmpdir=/tmp -q
```

### "Tests timeout"

Increase timeout for slow CI environments:

```bash
timeout 600 LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh
```

## Performance Optimization

### Parallel Test Execution

Modify `run-tests.sh` to run scenarios in parallel:

```bash
scenario_1_plugin_workflow &
PID1=$!

scenario_2_plugin_installation &
PID2=$!

scenario_3_reporter_plugin &
PID3=$!

wait $PID1 $PID2 $PID3
```

### Caching Gradle Dependencies

```yaml
# GitHub Actions
steps:
  - uses: actions/setup-java@v3
    with:
      cache: gradle

# GitLab CI
cache:
  paths:
    - .gradle/wrapper/
    - .gradle/caches/
```

### Skipping Full Rebuilds

```bash
# Use incremental builds in CI
./gradlew :cli:shadowJar -x test --build-cache

# Run integration tests
LOG_DIR=build/integration-logs ./tools/integration-tests/run-tests.sh
```

## Reporting

### JUnit XML Format

Modify test script to generate JUnit XML output:

```bash
# Add to run-tests.sh
export JUNIT_OUTPUT="build/integration-logs/junit-results.xml"
```

### HTML Report Generation

```bash
# Generate HTML report from test logs
./scripts/generate-html-report.sh build/integration-logs/
```

## Related Documentation

- [Integration Tests README](./README.md)
- [Plugin Development Guide](../docs/plugins/creating-plugins.md)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [GitLab CI Documentation](https://docs.gitlab.com/ee/ci/)
