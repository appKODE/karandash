# SARIF Reporter

## Overview

The SARIF Reporter outputs validation results in [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) (Static Analysis Results Interchange Format). SARIF is an industry-standard format supported by GitHub Code Scanning, GitLab SAST, VS Code, and many other SAST tools and CI platforms.

## How it works

The SARIF reporter uses **`SarifFormatModule`** to produce a SARIF 2.1.0 log with a single run entry. Output is delivered by `StdoutOutputModule` by default, or `FilesystemOutputModule` when `--output` is specified.

## Configuration

### YAML

```yaml
reporters:
  sarif: true
```

### With Version Override

```yaml
reporters:
  sarif:
    version: "1.2.3"
```

### JSON

```json
{
  "reporters": {
    "sarif": true
  }
}
```

### Write to File

```bash
karandash validate --format sarif --output results.sarif
```

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `version` | string | Karandash version | Override the tool version embedded in the SARIF `driver.version` field |

## Output Structure

The reporter produces a SARIF 2.1.0 log with a single run:

```json
{
  "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
  "version": "2.1.0",
  "runs": [
    {
      "tool": {
        "driver": {
          "name": "karandash",
          "version": "1.0.0",
          "informationUri": "https://github.com/karandash/karandash",
          "rules": [
            {
              "id": "title-format",
              "shortDescription": { "text": "Rule: title-format" },
              "fullDescription": { "text": "Validation rule: title-format" }
            }
          ]
        }
      },
      "results": [
        {
          "ruleId": "title-format",
          "level": "error",
          "message": { "text": "MR title does not match required pattern: ^(feat|fix): .+" },
          "locations": []
        },
        {
          "ruleId": "security",
          "level": "error",
          "message": { "text": "Potential secret detected" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": { "uri": "src/config.py" },
                "region": { "startLine": 42 }
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### Severity Mapping

| Karandash severity | SARIF level |
|-------------------|-------------|
| `ERROR` | `error` |
| `WARNING` | `warning` |
| `INFO` | `note` |

### Location Mapping

- `Location.FileRef` (file path + optional line) maps to a SARIF `physicalLocation` with `artifactLocation.uri` and optional `region.startLine`.
- `Location.CommitRef` (commit SHA) maps to a SARIF `physicalLocation` with `artifactLocation.uri` set to `commit:{sha}`.
- Findings without a location have an empty `locations` array.

## Platform Integration

### GitHub Code Scanning

```yaml
# .github/workflows/karandash.yml
- name: Run Karandash
  run: karandash validate --format sarif --output karandash.sarif

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: karandash.sarif
```

Results appear in the **Security > Code scanning alerts** tab.

### GitLab SAST

```yaml
# .gitlab-ci.yml
karandash:
  script:
    - karandash validate --format sarif --output gl-sast-report.json
  artifacts:
    reports:
      sast: gl-sast-report.json
```

Results appear in the MR's **Security** widget.

## Notes

- `message.text` appends the labelled `Where:`, `How to fix:` and `Format:` lines after a blank line; `properties` keeps
  the raw metadata.
- Each unique rule ID in the findings is registered as a SARIF `rule` in the tool driver. Rules are deduplicated automatically.
- The `version` override is useful when embedding results in a system that tracks tool versions separately from the Karandash binary version.

## Related Reporters

- [JSON](json.md) - Simpler JSON output without SARIF structure
- [Reviewdog](reviewdog.md) - Reviewdog-compatible format for inline PR annotations
- [Console](console.md) - Human-readable terminal output
