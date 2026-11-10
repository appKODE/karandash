# Known limits

Deliberate simplifications that have a ceiling. Each one is marked in the code with a `limit:` comment that
names the ceiling and the upgrade path; this page lists them so the trade-off is visible without reading the
source. None of them is a bug: the behavior stays correct, it only gets slower, coarser or less precise past the
ceiling. Change one only when a real workload reaches its limit.

| Area | Where | Ceiling | Upgrade path |
|------|-------|---------|--------------|
| Watch mode on Windows | `core/watcher` `PlatformFileWatcher.mingw.kt` | Polls modification time and size, so a change is seen up to one poll interval late. | `ReadDirectoryChangesW` |
| Diff hunks | `plugins/helpers` `UnifiedDiff.kt` | O(n*m) LCS over the changed middle; past `MAX_LCS_CELLS` the middle becomes one remove+add block (a correct diff, but not a minimal one). | Myers diff |
| AWS CodeCommit commits | `AwsCodeSuiteClient.fetchCommitsBetween` | Walks first parents only. A source branch that merged the destination in walks past the merge base until it reaches the commit cap. | Walk all parents against the destination's ancestry |
| AI dependency context | `DependencyContextBuilder` | Import resolution guesses from paths and has no compiler model. Wildcard and aliased imports are skipped. | A language-aware resolver |
| Shared module shutdown | `SharedModuleLeases` | One global `Mutex` for acquire and final shutdown. | Per-module locks |
| Run-session handles | `DefaultRunSession` | One `Mutex` per session serializes every handle initialization. | Per-key locks |
| JS plugin timeout | `JsCallGuard` | A QuickJS call cannot be interrupted. After a timeout the engine is abandoned, and its thread stays busy until the process exits. | QuickJS interrupt handler, once quickjs-kt exposes one |
| Platform client reinit | `ReinitializableClient` | An overlapping run with a different config can see a replaced client closed in the middle of a request. | Per-run module handles |
| Native plugin inspection | `NativePluginInspector` | Searches the decoded file for ASCII symbol names. A matching name inside a string constant counts as a false positive. | Parse the export/symbol tables |
| Reporter modules | `ReporterModuleFormat` | Always `ModuleScope.SHARED`, so a `PER_RUN` reporter gets the shared instance. | Wire its run handle |
| AI rule index | `ModuleInitializer` | Re-registers the AI module after init to refresh its config-dependent rules in the shared index. | Look rules up through the run |
| Sibling MR lookup | `PipelineExecutionStage.provideOpenMergeRequests` | One full MR fetch per open sibling. | A changes-only platform call |
| Blame data | `BlameFill` | At most `MAX_BLAME_FILES` (50) files per MR, fetched 4 at a time. | Raise the cap |
| SSRF host check | `SsrfUrlValidator.requirePublicAddresses` | Checks then connects. The engine resolves the name again, so a 0-TTL record flipped between the two is not caught. On the JVM, `InetAddress` reports NXDOMAIN and resolver failures alike, so a failed lookup passes (the engine then hits the same negative cache); native targets refuse any failure other than "no such name". | Pin the resolved address in the engine: CIO has a `dnsResolver` hook, the curl and WinHTTP engines have none |
| AWS credentials | `core/crypto` `AwsCredentialChain` | Static keys only: env, `~/.aws/credentials` and `~/.aws/config`. SSO, `role_arn`/`source_profile`, `credential_process`, web identity and ECS/IMDS are not read (IMDS is also blocked by SSRF protection). | Add providers to the chain as needed; `credential_process` needs a sanctioned subprocess path |
| CodeCommit request signing | `AwsCodeSuiteClient` | Signs with the canonical URI `/`, the path of the AWS endpoint. A custom proxy endpoint with a percent-escaped path is unsupported. | Sign the endpoint's own path |
