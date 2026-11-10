// Example Karandash validation script
// Runs in sandbox (QuickJS) by default - no Node.js required
// Signatures: karandash.fail/warn/message(msg, file?, line?, fix?) - see karandash-script.d.ts.
// `msg` states the problem; the optional `fix` says how to fix it and becomes the finding suggestion.

const mr = karandash.mr;

// Enforce PR title conventions
if (mr.title.length < 10) {
    karandash.fail("PR title is too short (minimum 10 characters)", null, null,
        "Expand the title to at least 10 characters describing the change.");
}

// Warn about large PRs
if (mr.changedFiles && mr.changedFiles.length > 20) {
    karandash.warn("This PR touches " + mr.changedFiles.length + " files", null, null,
        "Split the PR into parts of at most 20 files.");
}

// Check for WIP markers in description
if (mr.description && mr.description.match(/\bTODO\b|\bFIXME\b|\bWIP\b/i)) {
    karandash.warn("Description contains TODO/FIXME/WIP markers", null, null,
        "Resolve or remove the TODO/FIXME/WIP markers from the description.");
}

// Log for debugging
karandash.log("Validated MR: " + mr.title);
