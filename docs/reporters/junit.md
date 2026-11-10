# JUnit Reporter

## Overview

The JUnit Reporter outputs validation findings in JUnit XML format. CI platforms that support test report parsing (GitLab, GitHub Actions, Jenkins, CircleCI) can ingest this format and display findings alongside unit test results - no custom integration required.

## How it works

`JunitFormatModule` maps each finding to a JUnit `<testcase>` element. Findings with severity `ERROR` or `WARNING` become `<failure>` elements; `INFO` findings become `<system-out>` elements. The output is delivered by `FilesystemOutputModule` when `--output` is specified, or to stdout otherwise.

## Configuration

### YAML

```yaml
reporters:
  junit: true
```

### Write to file (recommended)

```bash
karandash validate --format junit --output karandash-results.xml
```

Or set the file in the config, so a plain `karandash validate` writes it - `output` is a file path (relative to
the working directory) unless it names an output module such as `webhook`:

```yaml
reporters:
  junit:
    output: karandash-results.xml
```

## Output Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="karandash" tests="3" failures="3" errors="0">
  <testsuite name="title-format" tests="1" failures="1" errors="0">
    <testcase name="title-format" classname="karandash.rules">
      <failure message="MR title does not match required pattern: ^(feat|fix): .+" type="ERROR">
Rule: title-format
Severity: ERROR
Message: MR title does not match required pattern: ^(feat|fix): .+
      </failure>
    </testcase>
  </testsuite>
  <testsuite name="size-limits" tests="1" failures="1" errors="0">
    <testcase name="size-limits" classname="karandash.rules">
      <failure message="MR title too long (max: 72, actual: 85)" type="WARNING">
Rule: size-limits
Severity: WARNING
Message: MR title too long (max: 72, actual: 85)
      </failure>
    </testcase>
  </testsuite>
  <testsuite name="info-rule" tests="1" failures="0" errors="0">
    <testcase name="info-rule" classname="karandash.rules">
      <system-out>Rule: info-rule
Severity: INFO
Message: MR has 3 commits</system-out>
    </testcase>
  </testsuite>
</testsuites>
```

### Severity mapping

| Karandash severity | JUnit element |
|--------------------|--------------|
| `ERROR` | `<failure>` |
| `WARNING` | `<failure>` |
| `INFO` | `<system-out>` |

## CI Integration

### GitLab - Test reports widget

```yaml
# .gitlab-ci.yml
karandash:
  script:
    - karandash validate --format junit --output karandash-results.xml
  artifacts:
    reports:
      junit: karandash-results.xml
```

Findings appear in the **Tests** tab of the MR and pipeline view.

### GitHub Actions - Test results

```yaml
- name: Run Karandash
  run: karandash validate --format junit --output karandash-results.xml

- name: Publish test results
  uses: EnricoMi/publish-unit-test-result-action@v2
  with:
    files: karandash-results.xml
```

### Jenkins - JUnit plugin

```groovy
sh 'karandash validate --format junit --output karandash-results.xml'
junit 'karandash-results.xml'
```

## Notes

- The JUnit reporter has no configuration options beyond `enabled`.
- The failure text adds labelled `Where:`, `How to fix:` and `Format:` lines when the finding carries them.
- Each unique rule that produced a finding generates one `<testcase>` element. If a rule produces multiple findings, they are concatenated into a single failure message.

## Related Reporters

- [SARIF](sarif.md) - SARIF 2.1.0 for GitHub Code Scanning / GitLab SAST
- [Reviewdog](reviewdog.md) - Inline PR annotations via reviewdog
- [JSON](json.md) - Machine-readable JSON output
