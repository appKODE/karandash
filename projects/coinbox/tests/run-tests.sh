#!/usr/bin/env bash
# Runs projects/coinbox/tests/{valid,invalid}-scenarios.yaml through the cli scenario tests.
# the previous gradle jvmRun/--scenario call matched no CLI option and read a config that was not in the tree.
exec bash "$(dirname "$0")/../../../tools/ci/run-project-scenarios.sh" coinbox
