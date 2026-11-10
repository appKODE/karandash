package io.karandash.cli.dangerfile

/**
 * Embedded Node.js bridge script that evaluates a JavaScript Dangerfile.
 *
 * The script:
 * 1. Reads DangerDSL JSON from a file path passed as `process.argv[3]`
 * 2. Builds a `danger` global from the parsed DSL
 * 3. Sets up `warn`, `fail`, `message`, `markdown`, `schedule` globals
 * 4. Strips `import ... from "danger"` / `require("danger")` lines
 * 5. Evaluates the Dangerfile via `vm.runInContext`
 * 6. Writes DangerResults JSON to a temp file
 * 7. Prints `danger-results:/<path>` sentinel to stdout
 *
 * Only Node.js built-ins are used (fs, path, os, vm). No npm install required.
 */
internal const val NODE_BRIDGE_SCRIPT: String = """'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const vm = require('vm');

const dangerfilePath = process.argv[2];
const dslPath = process.argv[3];
if (!dangerfilePath || !dslPath) { process.stderr.write('Missing args\n'); process.exit(1); }

const dsl = JSON.parse(fs.readFileSync(dslPath, 'utf8'));
const findings = { fails: [], warnings: [], messages: [], markdowns: [] };

const warn     = (msg, file, line) => findings.warnings.push({ message: String(msg || ''), ...(file ? { file } : {}), ...(line !== undefined ? { line } : {}) });
const fail     = (msg, file, line) => findings.fails.push({ message: String(msg || ''), ...(file ? { file } : {}), ...(line !== undefined ? { line } : {}) });
const message  = (msg, file, line) => findings.messages.push({ message: String(msg || ''), ...(file ? { file } : {}), ...(line !== undefined ? { line } : {}) });
const markdown = (msg) => findings.markdowns.push({ message: String(msg || '') });
const schedule = () => {};

const dangerObj = {
  git: dsl.git || {},
  github: dsl.github || {},
  gitlab: dsl.gitlab || {},
  utils: { href: (url, text) => '[' + (text || url) + '](' + url + ')' },
};

let src = fs.readFileSync(dangerfilePath, 'utf8');
src = src.replace(/^import\s+.*from\s+['"]danger['"]\s*;?\s*$/gm, '');
src = src.replace(/^const\s+\{[^}]*\}\s*=\s*require\(['"]danger['"]\)\s*;?\s*$/gm, '');
src = src.replace(/^var\s+\{[^}]*\}\s*=\s*require\(['"]danger['"]\)\s*;?\s*$/gm, '');
src = src.replace(/^let\s+\{[^}]*\}\s*=\s*require\(['"]danger['"]\)\s*;?\s*$/gm, '');

const ctx = vm.createContext({
  danger: dangerObj, warn, fail, message, markdown, schedule,
  console, process, require, Buffer, __dirname: path.dirname(path.resolve(dangerfilePath)),
  __filename: path.resolve(dangerfilePath), setTimeout, clearTimeout, setInterval, clearInterval,
});
try {
  vm.runInContext(src, ctx, { filename: dangerfilePath });
} catch (err) {
  process.stderr.write('Dangerfile error: ' + err.message + '\n');
  process.exit(2);
}

const out = path.join(os.tmpdir(), 'karandash-danger-' + Date.now() + '-' + process.pid + '.json');
fs.writeFileSync(out, JSON.stringify(findings));
console.log('danger-results:/' + out);
"""
