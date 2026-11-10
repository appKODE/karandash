#!/usr/bin/env bash
# Regression: the CI workflows and the local gate run the same checks.
#  The tools/detekt-rules tests run in gate.sh, which the ci.yml gate job runs.
#  CI checks test coverage, runs `mkdocs build --strict` and the benchmark regression check (native and jar).
#  The release publishes Pages (mkdocs + dokka) and CI packages the VS Code extension with vsce.
cd "$(dirname "$0")/../.." || exit 1
CI=.github/workflows/ci.yml
RELEASE=.github/workflows/release-build.yml
. tools/ci/lib.sh
has() { ci_job "$1" "$2" | grep -qF -- "$3" || fail "$4: job '$1' in $2 does not contain '$3'"; }
in_file() { grep -qF -- "$2" "$1" || fail "$3: $1 does not contain '$2'"; }
has gate "$CI" 'bash tools/ci/gate.sh' gate
has gate2 "$CI" 'bash tools/ci/gate2.sh' checks
in_file tools/ci/gate.sh ':tools:detekt-rules:test' gate
in_file tools/ci/gate.sh 'tools/ci/verify-test-coverage.sh' checks
in_file tools/ci/gate.sh 'koverVerify' checks
in_file tools/ci/gate2.sh 'mkdocs" build --strict' checks
in_file tools/ci/gate.sh 'benchmark-regression-check.sh' checks
in_file tools/ci/gate.sh 'BENCH_VARIANT=jar' checks
has pages "$RELEASE" 'docs/deploy.sh build public' pages
has pages "$RELEASE" 'dokkaGenerate' pages
has pages "$RELEASE" 'build/dokka/html' pages
has pages "$RELEASE" 'upload-pages-artifact' pages
has pages-deploy "$RELEASE" 'deploy-pages' pages
has vscode "$CI" 'npm run package' pages
for f in .github/workflows/*.yml .github/actions/*/action.yml; do
    python3 -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))' "$f" 2>/dev/null || ruby -ryaml -e 'YAML.load_file(ARGV[0])' "$f" ||
        fail "$f is not valid YAML"
done
echo "ci-jobs OK"
