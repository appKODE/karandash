// Typings for Karandash validation scripts (`scripts:` config block), sandbox and subprocess modes.
// Reference it from a script with: /// <reference path="./karandash-script.d.ts" />

/** The merge request under validation (the `MergeRequest` model plus the derived fields below). */
interface KarandashMergeRequest {
    title: string;
    description: string | null;
    sourceBranch: string;
    targetBranch: string;
    author: { username: string; [key: string]: unknown };
    commits: Array<Record<string, unknown>>;
    /** Paths of all changed files. */
    changedFiles: string[];
    /** Lines added across all files. */
    additions: number;
    /** Lines removed across all files. */
    deletions: number;
    [key: string]: unknown;
}

/**
 * Reports a finding.
 * @param msg the problem only: what is wrong and why
 * @param file path to anchor the finding to, or null
 * @param line line in [file], or null
 * @param fix one imperative sentence saying how to fix it; becomes the finding suggestion
 */
type KarandashReport = (msg: string, file?: string | null, line?: number | null, fix?: string | null) => void;

declare const karandash: {
    readonly mr: KarandashMergeRequest;
    /** Error finding; fails validation. */
    fail: KarandashReport;
    /** Warning finding. */
    warn: KarandashReport;
    /** Info finding. */
    message: KarandashReport;
    /** Debug log line; not shown by reporters. */
    log(msg: string): void;
    /** An env var, or null when unset, denylisted or not in the entry's declared `env`. */
    env(name: string): string | null;
};

/** Danger.js compatibility globals. */
declare const danger: {
    github: { pr: KarandashMergeRequest };
    gitlab: { mr: KarandashMergeRequest };
    git: Record<string, unknown>;
};
declare const fail: KarandashReport;
declare const warn: KarandashReport;
declare const message: KarandashReport;
declare function markdown(msg: string): void;
