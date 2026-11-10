#!/usr/bin/env bash
# Regression: runs one project's valid and invalid scenario files through the real scenario tests
# (ProjectConfigValidScenariosTest / ProjectConfigInvalidScenariosTest in cli). Usage: run-project-scenarios.sh <project>
set -euo pipefail
project="${1:?usage: run-project-scenarios.sh <project>}"
cd "$(dirname "$0")/../.."
[ -d "projects/$project/tests" ] || { echo "scenarios: no projects/$project/tests"; exit 1; }
log="build/project-scenarios-$project.log"
mkdir -p build
./gradlew --max-workers=3 :cli:jvmTest --rerun \
    --tests "io.karandash.cli.commands.projects.ProjectConfigValidScenariosTest.$project project - valid scenarios" \
    --tests "io.karandash.cli.commands.projects.ProjectConfigInvalidScenariosTest.$project project - invalid scenarios" \
    > "$log" 2>&1 || { tail -30 "$log"; echo "FAIL $project"; exit 1; }
grep -qE "$project project - (valid|invalid) scenarios.*PASSED" "$log" || { echo "scenarios: no scenario test ran for $project"; exit 1; }
echo "PASS $project"
