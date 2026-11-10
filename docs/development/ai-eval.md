# AI Eval Harness

The AI eval harness measures how well the 15 AI analyzers find what they should and stay quiet when they
should. It replays recorded LLM responses for a small labelled corpus of merge requests, scores the findings
against the labels and fails the build when precision, recall or F1 drop below a checked-in baseline.

It lives in the `plugins/integration-tests/ai-eval` module (`commonTest`, so it runs on JVM and native) as part of `jvmTest` and
`macosArm64Test`/`linuxX64Test`. The default mode is offline and deterministic: no provider credentials, no
network. Live mode is JVM-only in practice: native test tasks blank `KARANDASH_EVAL_LIVE`.

## Running

```bash
./gradlew :plugins:integration-tests:ai-eval:jvmTest
```

Each run writes two reports:

- `plugins/integration-tests/ai-eval/build/reports/ai-eval/report.json`: precision, recall, F1 and TP/FP/FN per
  analyzer and overall, plus every miss (false positive or false negative) with its case and analyzer.
- `plugins/integration-tests/ai-eval/build/reports/ai-eval/summary.txt`: the same numbers as a short table.

The gate test fails if any analyzer, or the overall score, is below the baseline in
`plugins/integration-tests/ai-eval/src/commonTest/resources/eval/baseline.json`. The failure message lists each
regressed metric next to the summary. A second test checks coverage: every analyzer must be scored and have at
least one positive label, and the corpus must contain at least one known-clean MR.

## Layout

All data sits under `plugins/integration-tests/ai-eval/src/commonTest/resources/eval/`:

| Path | Content |
|------|---------|
| `corpus/*.json` | One labelled MR per file (`EvalCase`) |
| `recorded/*.json` | Recorded LLM response per analyzer for the case with the same file name |
| `baseline.json` | The measured scores the gate compares against |

A corpus case holds the MR (title, description, labels, commit messages, files with unified diff lines), an
optional design doc (passed as extra instructions), optional prior findings (fed to `fix-suggestions` as
accumulated findings), the analyzers to run and the expected findings. A trimmed example:

```json
{
  "id": "01-example",
  "title": "Add user lookup by email",
  "files": [
    { "path": "src/main/kotlin/UserRepository.kt", "diff": ["@@ -10,3 +10,8 @@", "+fun findByEmail(email: String) ="] }
  ],
  "analyzers": ["code-quality", "risk-scoring"],
  "expected": [
    { "analyzer": "code-quality", "file": "src/main/kotlin/UserRepository.kt", "startLine": 12, "category": "security" },
    { "analyzer": "risk-scoring" }
  ]
}
```

An analyzer listed in `analyzers` with no `expected` entry for it must produce no findings: any finding is a
false positive. The clean cases rely on this.

## Matching

Each finding matches at most one label, and each label at most one finding. A finding matches a label of the same
analyzer when:

- **File:** if the label names a file, the finding carries a `FileRef` to the same path (leading `./` and `/`
  ignored). If both sides have lines, the ranges must overlap within a 3-line window. A finding without a line
  matches on the file alone.
- **Category:** if the label names a category, some finding metadata value equals it ignoring case, either whole
  or as one item of a comma- or semicolon-separated list. This covers `category`, `dimension`, suggested labels,
  risk level and so on.

A label with neither file nor category matches any finding of its analyzer. A further finding that would only
match an already-matched label counts as a duplicate, not as a false positive.

## Full-pipeline replay

`EvalReplayTest` also replays every case through the real `validate` pipeline (`ValidationPipeline`): config
loading and AI rule synthesis, the AI rule with prompt assembly, redaction and the interceptor chain, the AI
pipeline extension filters, markdown formatting and platform output. Only the LLM (a cassette interceptor) and the
platform API are fake. Each case runs in a fresh temp directory, so no cache leaks between runs.

Per case it captures the exact prompts after redaction, the findings left after filtering, the rendered summary,
the inline notes and stage counters. Golden snapshots sit in `replay/<id>.md`. The test also checks that two runs
give byte-identical prompts, summary and notes. The run log goes to `build/reports/ai-eval/replay/<id>.log`.

Two optional corpus keys feed the replay:

- `cassette`: analyzer ID to the raw response text. It wins over `recorded/<id>.json`, for instance for an
  analyzer that only the full pipeline calls.
- `minedFrom`: where a mined case came from (`group/project!123`). Informational only.

The baseline's `pipeline` section holds per-case notes, kept and dropped findings, silent drops and estimated
tokens (prompt characters / 4). The gate fails if notes grow more than 10%, estimated tokens more than 15%, or any
silent drop appears. A silent drop is an analyzer that sent no prompt, produced no finding and logged no skip
reason, or a cassette miss.

## Updating the baseline

After an intentional change in analyzer behaviour, prompts or recordings, rewrite the baseline and the replay
goldens from the current run, review the diff and commit it with the change:

<!-- docs-test: skip -->
```bash
./gradlew :plugins:integration-tests:ai-eval:jvmTest -Dkarandash.updateBaseline=true -Dkarandash.updateGolden=true
```

`KARANDASH_EVAL_UPDATE_BASELINE=1` still works for the baseline.

## Live mode and re-recording

Live mode sends the corpus to a real provider instead of replaying it. It never runs by default, and the gate is
not enforced in live mode: it writes the report so you can compare live quality with the baseline.

| Variable | Effect |
|----------|--------|
| `KARANDASH_EVAL_LIVE=1` | Call a real provider |
| `KARANDASH_EVAL_RECORD=1` | With live mode, overwrite `recorded/*.json` with the live responses |
| `KARANDASH_EVAL_PROVIDER` | `anthropic` (default) or `openai` |
| `KARANDASH_EVAL_MODEL` | Model to use; defaults to the provider's default model |
| `KARANDASH_EVAL_BASE_URL` | Optional provider endpoint override |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | Provider credentials |

<!-- docs-test: skip -->
```bash
KARANDASH_EVAL_LIVE=1 KARANDASH_EVAL_RECORD=1 ANTHROPIC_API_KEY=... \
  ./gradlew :plugins:integration-tests:ai-eval:jvmTest
KARANDASH_EVAL_UPDATE_BASELINE=1 ./gradlew :plugins:integration-tests:ai-eval:jvmTest
```

The second command re-scores the new recordings offline and writes the baseline. Review both diffs before
committing. The task is never up to date while `KARANDASH_EVAL_LIVE` is set, so Gradle does not reuse a cached
result.

!!! note
    The recordings checked in today are hand-authored seeds with deliberate imperfections, so the gate has
    something to measure. Re-record them against a real provider to get a baseline that reflects actual model
    quality.

## Adding a case

1. Add `corpus/NN-name.json` with the MR, the analyzers to run and the expected findings.
2. Record responses with live mode, or write `recorded/NN-name.json` by hand. Its `responses` object maps each
   analyzer ID to the raw response, either a JSON object or a string.
3. Run the gate, check `summary.txt` and update the baseline if the change is intended.
