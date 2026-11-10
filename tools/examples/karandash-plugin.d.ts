// Typings for Karandash JS plugins (`.mjs`/`.js` modules loaded by the JS plugin loader).
// Reference it from a plugin with: /// <reference path="../karandash-plugin.d.ts" />

/** Where a finding points. Sealed on `type`; omit `location` (or pass null) for an MR-level finding. */
type KarandashLocation =
    | { type: "file"; path: string; line?: number | null; endLine?: number | null }
    | { type: "commit"; sha: string };

/** A finding as returned by `validate` / `analyze`. */
interface KarandashFinding {
    severity: "ERROR" | "WARNING" | "INFO";
    /** The problem only: what is wrong and why. */
    message: string;
    /** Rule (or analyzer) id that produced the finding. */
    rule: string;
    location?: KarandashLocation | null;
    /** One imperative sentence saying how to fix it. */
    suggestion?: string | null;
    explanation?: string | null;
    metadata?: Record<string, string>;
}

/** The merge request under validation (the `MergeRequest` model, JSON-decoded). */
interface KarandashMergeRequest {
    title: string;
    description: string | null;
    sourceBranch: string;
    targetBranch: string;
    author: { username: string; [key: string]: unknown };
    commits: Array<Record<string, unknown>>;
    changes: Array<{ path: string; oldPath?: string | null; changeType: string; additions: number; deletions: number; diffContent?: string | null; [key: string]: unknown }>;
    labels: string[];
    [key: string]: unknown;
}

/** A host-service failure. `retryable` is set by `ai.complete` when the provider says a retry may succeed. */
interface KarandashHostError {
    err: string;
    retryable?: boolean;
}

/** Request of `ctx.ai.complete`; a plain string is shorthand for `{ prompt }`. */
interface KarandashAiRequest {
    prompt: string;
    systemPrompt?: string;
    /** Defaults to the model of the running analyze call. */
    model?: string;
    /** Checked against the analyzer budget before the call (default 2000). */
    maxTokens?: number;
    temperature?: number;
    jsonMode?: boolean;
}

interface KarandashAiReply {
    content: string;
    model: string;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
}

/**
 * A content source, as in config: a plain string is inline text; `{file}` is relative to the config directory and
 * must stay inside it or its git checkout (1 MB cap). `{url}` and `{"repo-file"}` answer `{err}`: guests get no
 * network egress.
 */
type KarandashContentSource =
    | string
    | { inline: string }
    | { file: string }
    | { url: string; auth?: string }
    | { "repo-file": string; ref?: string };

/**
 * The context passed to `initialize(ctx)`. Keep it in a module variable to use the host services later;
 * every call is synchronous and never throws: failures come back as `{ err }`.
 */
interface KarandashPluginContext {
    /** This plugin's config block. */
    pluginConfig: unknown;
    /** Debug log line; `log.info/warn/error` log at that level. */
    log: ((message: string) => void) & {
        info(message: string): void;
        warn(message: string): void;
        error(message: string): void;
    };
    warn(message: string): void;
    /** Environment variable, filtered by the host denylist. */
    env(name: string): string | null;
    resolvePattern(pattern: string): string | null;
    /** String cache; keys are scoped to this plugin. */
    cache: {
        get(cache: string, key: string): string | null;
        put(cache: string, key: string, value: string): void;
    };
    content: { resolve(source: KarandashContentSource): { content: string } | KarandashHostError };
    /**
     * The host's AI service. Inside `analyze` it uses that call's service and budget (spend is recorded there);
     * the host adds the shared MR context blocks, so pass `config.instructions`, not `extraInstructions`.
     */
    ai: { complete(request: KarandashAiRequest | string): KarandashAiReply | KarandashHostError };
    /**
     * Inside `analyze` only: report this run as skipped with `reason` (shown as "skipped: reason", not as a run with
     * no findings). Same as returning `{ skip: reason }` from `analyze`; outside `analyze` it answers `{ err }`.
     */
    skip(reason: string): {} | KarandashHostError;
}

/** `configJson` of an AI_ANALYZER `analyze` call: the whole `ai:` block plus the fields below. */
interface KarandashAnalyzerConfig {
    /** Per-analyzer settings, by analyzer id. */
    rules?: Record<string, unknown>;
    aiServiceAvailable: boolean;
    /** Extra instructions only; use with `ctx.ai.complete`, which adds the context blocks itself. */
    instructions?: string;
    /** Context blocks plus extra instructions, for a plugin that calls its own provider. */
    extraInstructions?: string;
    resolvedPrompt?: string;
    [key: string]: unknown;
}

/**
 * An AI_ANALYZER plugin. `analyze` returns findings (an empty array when there is nothing to report), or
 * `{ skip: reason }` when it declines the run (missing input, budget too small).
 */
interface KarandashAiAnalyzerPlugin {
    id: string;
    name: string;
    version: string;
    type: "AI_ANALYZER";
    analyzerId?: string;
    featureKey?: string;
    defaultPrompt?: string;
    enabledByDefault?: boolean;
    initialize?(ctx: KarandashPluginContext): boolean;
    shutdown?(): void;
    analyze(
        mr: KarandashMergeRequest,
        budget: { remaining: number },
        model: string,
        config: KarandashAnalyzerConfig,
    ): KarandashFinding[] | { skip: string };
}

/**
 * Answer of a platform `Result` op (`addReviewers`, `removeReviewers`, `mergeMr`, `closeMr`, `reopenMr`,
 * `resolveThread`, `replyToThread`, `updateNote`). `unsupported: true` means the platform cannot do it at all;
 * a plugin that does not export the op is answered as unsupported, with one warn.
 */
type KarandashResultEnvelope = { ok: true } | { err: string; unsupported?: boolean };

/**
 * Optional data exports of a RULE plugin. Each is optional: a missing export keeps the SDK default
 * (no usage, no token count, no required fields). Exporting `validateFile` or `validateWithFindings` makes the
 * rule file-granular or findings-aware.
 */
interface KarandashRuleDataExports {
    validateFile?(ruleId: string, file: Record<string, unknown>, mr: KarandashMergeRequest, config: unknown): KarandashFinding[];
    validateWithFindings?(
        ruleId: string,
        mr: KarandashMergeRequest,
        config: unknown,
        context: { priorFindings: KarandashFinding[] },
    ): KarandashFinding[];
    usage?: { tokens: number; requests: number } | null;
    /** `MergeRequestField` names; unknown names are ignored. */
    requiredMergeRequestFields?(ruleId: string, config: unknown): string[];
    tokensConsumed?(): number | null;
    logCostSummary?(): string;
}
