# NAV Project Validation Rules Reference

This document describes all validation rules enforced on Git commits and Merge Requests
for the NAV project, with examples of correct and incorrect formatting.

## Configuration

All rules are configured in `.karandash/config.yaml`. See the [configuration file](../.karandash/config.yaml) for the full setup.

---

## 1. MR Title Format (`title-format`)

**Requirement:** MR title must follow the format `[NAV-{id}]...[ContextTag] Title text`

- At least one NAV task ID is required
- Exactly one context tag (`[ios]`, `[android]`, or `[core]`) is required
- Context tag must be the last tag before the title text
- Title text must be non-empty
- Total length: 10–120 characters

### Examples

| Input | Status | Reason |
|-------|--------|--------|
| `[NAV-123][ios] Add login screen` | ✓ Correct | Single task, ios tag |
| `[NAV-123][NAV-456][android] Refactor navigation` | ✓ Correct | Multiple tasks, android tag |
| `[NAV-789][core] Update shared models` | ✓ Correct | Single task, core tag |
| `[ios] Add feature` | ✗ Incorrect | Missing task ID |
| `[NAV-123] Fix bug` | ✗ Incorrect | Missing context tag |
| `[NAV-123][backend] Fix bug` | ✗ Incorrect | Invalid context tag (only ios/android/core allowed) |
| `[ios][NAV-123] Feature` | ✗ Incorrect | Context tag must come after task IDs |
| `[NAV-123][ios]` | ✗ Incorrect | Missing title text after tags |
| `Fix login bug` | ✗ Incorrect | Missing task ID and context tag |

---

## 2. Commit Title Format (`commit-messages`)

**Requirement:** Commit titles follow the same format as MR titles: `[NAV-{id}]...[ContextTag] Title text`

- At least one NAV task ID is required
- Exactly one context tag (`[ios]`, `[android]`, or `[core]`) is required
- Context tag must be the last tag before the title text
- Title text must be non-empty
- Total length: 10–72 characters (conventional Git commit limit)

### Examples

| Input | Status | Reason |
|-------|--------|--------|
| `[NAV-123][ios] Implement login UI` | ✓ Correct | Standard format |
| `[NAV-123][NAV-456][core] Shared logic` | ✓ Correct | Multiple task IDs |
| `fix: add login screen` | ✗ Incorrect | Wrong format (conventional commits not used) |
| `Updated the code` | ✗ Incorrect | Missing task ID and context tag |
| `[ios] Quick fix` | ✗ Incorrect | Missing task ID |
| `[NAV-123] No context tag` | ✗ Incorrect | Missing context tag |

---

## 3. Context Tag Validation (`context-tag`)

**Requirement:** Exactly one context tag must be present in both MR and commit titles.

Allowed values: `[ios]`, `[android]`, `[core]`

Context tags are case-insensitive.

### Examples

| Input | Status | Reason |
|-------|--------|--------|
| `[NAV-123][ios] Feature` | ✓ Correct | Single allowed tag |
| `[NAV-123][android] Feature` | ✓ Correct | Single allowed tag |
| `[NAV-123][core] Feature` | ✓ Correct | Single allowed tag |
| `[NAV-123][iOS] Feature` | ✓ Correct | Case-insensitive matching |
| `[NAV-123][ANDROID] Feature` | ✓ Correct | Case-insensitive matching |
| `[NAV-123] Feature` | ✗ Incorrect | No context tag |
| `[NAV-123][ios][android] Feature` | ✗ Incorrect | Multiple context tags |
| `[NAV-123][backend] Feature` | ✗ Incorrect | Invalid context tag |
| `[NAV-123][web] Feature` | ✗ Incorrect | Invalid context tag |
| `[NAV-123][iOS][android] Feature` | ✗ Incorrect | Multiple context tags (both present) |

---

## 4. Commit Description / Body Format (`commit-body`)

**Requirement:** Commit description must start with a CHANGELOG line in the format:

```
CHANGELOG: [NAV-{TaskID1}] Task name 1, [NAV-{TaskID2}] Task name 2.
```

- The CHANGELOG line must be the first line of the commit body
- It must start with the uppercase word `CHANGELOG:`
- It must contain at least one task reference: `[NAV-{id}] description`
- Multiple tasks are separated by commas
- The line must end with a period (`.`)
- Optional explanatory text may follow the CHANGELOG line

### Examples

| Commit Message | Status | Reason |
|----------------|--------|--------|
| `[NAV-123][ios] Add feature`<br><br>`CHANGELOG: [NAV-123] Add login screen.` | ✓ Correct | Valid CHANGELOG line |
| `[NAV-123][NAV-456][ios] Work`<br><br>`CHANGELOG: [NAV-123] Login, [NAV-456] Auth.` | ✓ Correct | Multiple tasks in CHANGELOG |
| `[NAV-123][ios] Add feature`<br><br>`CHANGELOG: [NAV-123] Login screen.`<br><br>`Added the login screen with email/password fields.` | ✓ Correct | CHANGELOG + explanation |
| `[NAV-123][ios] Fix bug` | ✗ Incorrect | Missing body (CHANGELOG required) |
| `[NAV-123][ios] Fix`<br><br>`Just fixed the bug` | ✗ Incorrect | Body doesn't start with CHANGELOG |
| `[NAV-123][ios] Fix`<br><br>`changelog: [NAV-123] Fix.` | ✗ Incorrect | CHANGELOG must be uppercase |
| `[NAV-123][ios] Fix`<br><br>`CHANGELOG: [NAV-123] Fix` | ✗ Incorrect | CHANGELOG line must end with period |

---

## 5. MR Description Structure (`description-format`)

**Requirement:** MR description must contain these required sections:

```
{Optional description}

TASK: {Jira link 1} and {Jira link 2}

CHANGELOG: [NAV-{TaskID1}] Task name 1. [NAV-{TaskID2}] Task name 2.

Closes NAV-{TaskID1} and NAV-{TaskID2}

Proof: video or screenshots

Labels: ios/android/core/generator/android_auto
```

### Required Sections

| Section | Pattern | Required | Example |
|---------|---------|----------|---------|
| TASK | `TASK: {Jira links}` | Yes | `TASK: https://jira.example.com/browse/NAV-123` |
| CHANGELOG | `CHANGELOG: [NAV-{id}] Description.` | Yes | `CHANGELOG: [NAV-123] Login screen.` |
| Closes | `Closes NAV-{id}` | Yes | `Closes NAV-123` |
| Proof | `Proof: {evidence}` | Yes | `Proof: video at https://drive.google.com/...` |
| Labels | `Labels: {label}` | No | `Labels: ios` |

### Correct Example

```
Added new login screen with biometric authentication support.

TASK: https://jira.example.com/browse/NAV-123 and https://jira.example.com/browse/NAV-456

CHANGELOG: [NAV-123] Login screen. [NAV-456] Biometric auth.

Closes NAV-123 and NAV-456

Proof: video recording at https://drive.google.com/example

Labels: ios
```

### Incorrect Examples

**Missing TASK section:**
```
Just a description without task links.

CHANGELOG: [NAV-123] Feature.
Closes NAV-123
Proof: screenshot.png
```
Reason: TASK section is required.

**Missing Proof section:**
```
TASK: https://jira.example.com/NAV-123
CHANGELOG: [NAV-123] Feature.
Closes NAV-123
```
Reason: Proof section is required (video or screenshots).

**Missing CHANGELOG section:**
```
TASK: https://jira.example.com/NAV-123
Closes NAV-123
Proof: screenshot.png
```
Reason: CHANGELOG section is required.

**Empty description:**
_(completely blank or null)_
Reason: Description should describe the work.

---

## 6. Cross-Entity Consistency (`cross-entity-consistency`)

**Requirement:** Task ID references must be consistent across the MR and its commits.

Rules:
- All task IDs in commit titles must be a **subset** of the MR task IDs
- Number of unique task IDs found in commit CHANGELOG sections must not exceed the number of MR task IDs
- All CHANGELOG task references must be documented in MR task IDs

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `taskIdPattern` | `string` | `"[A-Z]+-\\d+"` | Regex for task ID extraction (generic Jira pattern) |
| `mrTaskSource` | `string` | `"title"` | Where to extract MR task IDs: `title`, `description`, or `both` |

### Examples

**Correct: Commit tasks are subset of MR tasks:**
```
MR title:     [NAV-123][NAV-456][ios] Feature work
Commit 1:     [NAV-123][ios] Part 1
Commit 2:     [NAV-456][ios] Part 2
→ {NAV-123, NAV-456} ⊆ {NAV-123, NAV-456} ✓
```

**Incorrect: Commit references task not in MR:**
```
MR title:     [NAV-123][ios] Single task MR
Commit 1:     [NAV-123][NAV-999][ios] Sneaky extra task
→ {NAV-123, NAV-999} ⊄ {NAV-123} ✗
Reason: NAV-999 is in the commit but not in the MR.
```

**Correct: CHANGELOG consistent:**
```
MR title:     [NAV-123][NAV-456][ios] Feature
Commit 1:     ... CHANGELOG: [NAV-123] Task one.
Commit 2:     ... CHANGELOG: [NAV-456] Task two.
→ 2 unique CHANGELOG task IDs ≤ 2 MR task IDs ✓
```

**Incorrect: CHANGELOG has extra tasks:**
```
MR title:     [NAV-123][ios] Single task
Commit 1:     ... CHANGELOG: [NAV-123] One, [NAV-456] Two.
→ 2 unique CHANGELOG task IDs > 1 MR task ID ✗
Reason: CHANGELOG references NAV-456 which is not in MR.
```

---

## 7. Jira Ticket Validation (`jira-ticket`)

**Requirement:** MR title must contain a valid NAV project Jira ticket reference.

- Project key must be `NAV`
- Ticket format: `[NAV-{number}]` where number is a positive integer
- At least one valid NAV ticket must be present

### Examples

| MR Title | Status | Reason |
|----------|--------|--------|
| `[NAV-123][ios] Feature` | ✓ Correct | Valid NAV ticket |
| `[NAV-1][core] Tiny fix` | ✓ Correct | Any positive number is valid |
| `[NAV-99999][android] Large ID` | ✓ Correct | Large numbers OK |
| `[PROJ-123][ios] Feature` | ✗ Incorrect | Wrong project key |
| `[JIRA-123][ios] Feature` | ✗ Incorrect | Wrong project key |
| `Feature without ticket` | ✗ Incorrect | No ticket reference |
| `NAV-123 without brackets` | ✗ Incorrect | Ticket must be in brackets |

---

## 8. Language Policy (`language-policy`)

**Requirement:** All Git entities must be written in the configured language (default: English / Latin script).

Applies to:
- MR title
- MR description
- Commit titles
- Commit descriptions
- Branch names

Latin characters with accents (é, ñ, ü, etc.) are allowed. Numbers, punctuation, and symbols are allowed.

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `allowedScripts` | `string[]` | `["latin"]` | Named script ranges: `latin`, `cyrillic`, `greek`, `arabic`, `cjk`, `hebrew`, `devanagari` |
| `additionalAllowedRanges` | `string[]` | `[]` | Custom Unicode ranges (e.g., `"0400-04FF"`) |
| `languageName` | `string` | `"English"` | Language name used in error messages |

For non-English teams, configure allowed scripts:
```yaml
language-policy:
  allowedScripts: ["latin", "cyrillic"]
  languageName: "English/Russian"
```

### Examples

| Entity | Input | Status | Reason |
|--------|-------|--------|--------|
| MR title | `[NAV-123][ios] Add login screen` | ✓ Correct | English text |
| MR title | `[NAV-123][ios] Update café résumé` | ✓ Correct | Accented Latin allowed |
| MR title | `[NAV-123][ios] Fix bug #456 (v2.0)` | ✓ Correct | Digits/symbols allowed |
| MR title | `[NAV-123][ios] Добавить фичу` | ✗ Incorrect | Cyrillic characters |
| MR title | `[NAV-123][ios] 添加功能` | ✗ Incorrect | Chinese characters |
| MR title | `[NAV-123][ios] إضافة ميزة` | ✗ Incorrect | Arabic characters |
| Commit | `[NAV-123][ios] Исправить баг` | ✗ Incorrect | Cyrillic in commit |
| Description | `Описание на русском` | ✗ Incorrect | Cyrillic in description |
| Branch | `feature/NAV-123-login` | ✓ Correct | Latin branch name |
| Commit | `[NAV-123][ios] Add καινή δυνατότητα` | ✗ Incorrect | Greek characters |

---

## 9. Labels (`labels`)

**Requirement:** Platform labels may be required or used based on context.

Available labels:
- **Platform labels:** `ios`, `android`, `core`
- **Feature labels:** `generator`, `android_auto`
- **Optional labels:** `redesign`, `refactoring`

Labels are specified in the MR description in the Labels section:

```
Labels: ios
```

or

```
Labels: android, redesign
```

### Examples

| MR Description Section | Status | Reason |
|------------------------|--------|--------|
| `Labels: ios` | ✓ Correct | Single platform label |
| `Labels: android, redesign` | ✓ Correct | Platform + optional label |
| `Labels: core, generator` | ✓ Correct | Platform + feature label |
| _(no Labels section)_ | ✓ Correct | Labels section is optional |
| `Labels: backend` | ✗ Incorrect | Invalid label |
| `Labels: iOS` | ✗ Incorrect | Labels are case-sensitive |

---

## 10. Draft Prevention (`draft-prevention`)

**Requirement:** Merge requests must not be in draft/WIP (Work in Progress) state.

The validator checks that:
- MR is not marked as draft
- MR title does not start with `Draft:` or `WIP:`
- MR is ready for review

This is automatically enforced when draft detection is enabled.

---

## 11. Platform-Specific Rules (Conditional)

These rules are applied conditionally based on the platform context tag in the MR title.

### iOS / Core Platform Changelog

When context tag is `[ios]` or `[core]`, an optional platform changelog section may be present:

```
iOS Changelog: [NAV-123][NAV-234][no_qa]
```

or

```
Core Changelog: [NAV-123][NAV-234][no_qa]
```

Rules:
- Unlimited task IDs allowed (not limited by MR task count)
- `[no_qa]` is optional. When present, QA verification is skipped for those tasks
- These sections are **optional** (not required)
- Automation may use this to automatically move Jira tickets:
  - Tasks **without** `[no_qa]` → Move to "On Check" status
  - Tasks **with** `[no_qa]` → Move to "Closed" status

### Android Platform Changelog

When context tag is `[android]`, the finalizing commit must contain a standard CHANGELOG line:

```
CHANGELOG: [NAV-123] Jira task title
```

The CHANGELOG format for Android commits is the same as standard commits. No special Android-specific format is required beyond the standard CHANGELOG line.

---

## Quick Reference Card

### Valid MR Checklist

- [ ] **Title format:** `[NAV-{id}][{platform}] Description`
  - Example: `[NAV-123][ios] Add login screen`
- [ ] **Exactly one platform tag:** `[ios]`, `[android]`, or `[core]`
- [ ] **Description contains TASK:** Line with Jira links
  - Example: `TASK: https://jira.example.com/browse/NAV-123`
- [ ] **Description contains CHANGELOG:** Task descriptions
  - Example: `CHANGELOG: [NAV-123] Login screen.`
- [ ] **Description contains Closes:** Task closing references
  - Example: `Closes NAV-123`
- [ ] **Description contains Proof:** Evidence (video/screenshots)
  - Example: `Proof: video at https://...`
- [ ] **All text in English** (Latin script only)
- [ ] **Not in draft state**

### Valid Commit Checklist

- [ ] **Title format:** `[NAV-{id}][{platform}] Description`
  - Example: `[NAV-123][ios] Implement feature`
- [ ] **Body starts with CHANGELOG:** Task descriptions
  - Example: `CHANGELOG: [NAV-123] Implement feature.`
- [ ] **Task IDs are subset of MR task IDs**
  - Example: If MR has `[NAV-123][NAV-456]`, commit can have `[NAV-123]`
- [ ] **Exactly one platform tag matching MR**
- [ ] **All text in English** (Latin script only)

### Full Valid Example

**MR Title:**
```
[NAV-123][NAV-456][ios] Add login screen with biometric auth
```

**MR Description:**
```
Added login screen with email/password and biometric authentication support.
Users can now log in with their credentials or use Touch ID / Face ID for quick access.

TASK: https://jira.example.com/browse/NAV-123 and https://jira.example.com/browse/NAV-456

CHANGELOG: [NAV-123] Login screen. [NAV-456] Biometric authentication.

Closes NAV-123 and NAV-456

Proof: video at https://drive.google.com/file/example

Labels: ios
```

**Commit 1:**
```
[NAV-123][ios] Implement login UI

CHANGELOG: [NAV-123] Login screen.

Added email and password input fields with validation.
Styled to match design system specifications.
```

**Commit 2:**
```
[NAV-456][ios] Add biometric authentication

CHANGELOG: [NAV-456] Biometric authentication.

Integrated TouchID and FaceID for quick login.
Falls back to password login if biometric unavailable.
```

---

## Validation Engine

The Karandash validation engine enforces these rules through the following flow:

1. **Configuration Loading:** Rules are loaded from `.karandash/config.yaml`
2. **MR Fetching:** Merge request data is fetched from the platform (GitLab, GitHub, etc.)
3. **Validation:** Each rule module validates the MR and commits
4. **Reporting:** Findings are reported through configured reporters (console, JSON, etc.)

All validations are **strict**: violations block the merge request.

---

## Integration with Karandash

To use these rules in your project:

1. **Ensure `.karandash/config.yaml` exists** in your repository root or `.karandash/` directory
2. **Configure the rules** you want to enforce (most are included by default)
3. **Run Karandash** as part of your CI/CD pipeline:
   ```bash
   karandash validate --url <merge-request-url>
   ```
4. **Fix violations** reported by the validator
5. **Merge** once all validations pass

See the [Karandash documentation](../../../README.md) for complete installation and usage instructions.
