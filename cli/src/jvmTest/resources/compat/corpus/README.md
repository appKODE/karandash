# Config corpus snapshot

Frozen effective-config output of every config shipped in the repo, produced by `CompatConfigTest`
(`cli/src/jvmTest/kotlin/io/karandash/cli/compat/`). Files are named after the source path:
`projects/<p>/.karandash/config.yaml` -> `projects_<p>_config.yaml.*`, `tools/examples/x.kts` -> `tools_examples_x.kts.*`.

- `*.effective.json` - `Config` after extends, `{ file: }` resolution, `${ENV}` interpolation, key normalization and
  profile resolution (absolute paths replaced by `<root>`).
- `*.warnings.txt` - every warning/error the loader logged. This is the warnings allowlist: a new warning fails the test.

Regenerate with `./gradlew :cli:jvmTest --tests 'io.karandash.cli.compat.CompatConfigTest' -Dkarandash.updateGolden=true`
and review the diff. Any change is a user-visible behavior change and belongs in CHANGELOG `## [Unreleased]`.

## Coverage

- `projects/*/.karandash/config.yaml` - staged under `cli/build/compat-corpus/` with deterministic stubs for every
  gitignored `{ file: "..." }` fragment, so a developer's local fragments don't leak into the snapshot.
- `tools/examples/*.kts` and `tools/examples/config-inheritance/*.yaml`.
- `docs/` ships no config files (only `mkdocs.yml`); docs-documented features are covered by
  `../configs/features/<feature>/config.yaml`, one per feature.
- Excluded: `tools/integration-tests/scenarios/**` (scenario harness inputs, exercised by their own runner) and
  `.karandash/config.*` at repo root (developer-local, gitignored).
- A config whose `${VAR}` references (or those of its extends chain) are set in the test environment is skipped with a
  console note, because interpolation reads the real process env.

## Known-wrong cases frozen as-is

These snapshots record today's behavior, not the intended one. Fixing them must update the golden and the CHANGELOG.

1. **Unset env vars become empty strings plus an E0022 warning** (`projects_*`): e.g.
   `token: "${KARANDASH_GITLAB_TOKEN}"` resolves to `"token": ""` and `apiKey` to `""` instead of failing or being
   left for the platform layer.
2. **Top-level `auth:` block is dropped from the effective config** (`../configs/features/auth`): the
   `compat-auth-block-token` value never reaches `Config`; only the inline `platforms.gitlab.token` is effective, while
   the block parses without any warning (docs/configuration/YAML_CONFIG.md says it is ignored).
3. **Fragment-only prompts are stubs** (`projects_*`): `{ file: }` references to gitignored prompt/scope files are
   replaced by `compat stub: <ref>`, so AI prompt content is not part of the snapshot.
4. **Policy `severity:` is never applied** (`../output/configs/policy-severity.yaml`, `output/exit-codes.txt`): a
   matching policy fills `ActivePolicy.forcedSeverity`, but nothing reads it, so the finding keeps its original
   severity and exit code.
5. **`--format rdjsonl` is rejected** (`output/exit-codes.txt`): `validate --help` lists `rdjsonl` as a built-in, but
   only `rdjson` is registered, so the run fails with exit 2.
