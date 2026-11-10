# Shared helpers for tools/ci scripts. Source it after `cd` to the repo root: `. tools/ci/lib.sh`.

# fail MSG : print "$FAIL_PREFIX: MSG" (just MSG when FAIL_PREFIX is unset) and exit 1
fail() { echo "${FAIL_PREFIX:+$FAIL_PREFIX: }$1"; exit 1; }

# ci_job NAME [FILE...] : the body (header line excluded) of GitHub Actions job NAME (a key under `jobs:`) in each
# FILE (default: $CI)
ci_job() {
  local name=$1
  shift
  [ $# -gt 0 ] || set -- "$CI"
  local f
  for f in "$@"; do awk -v name="  $name:" '$0 == name {on=1; next} on && (/^[^ #]/ || /^  [^ #]/) {exit} on' "$f"; done
}

# step_summary LOGFILE : what step prints for a failed step; override after sourcing to change it
step_summary() { tail -10 "$1"; }

# step NAME LOGNAME CMD... : run CMD into $L/LOGNAME.log, print "NAME rc", set FAILED=1, add NAME to FAILED_STEPS and
# summarize on failure
# GATE_STEPS="A B" runs only the named steps (CI splits gate.sh across jobs); unset runs all.
step() {
  local name=$1 log=$2
  shift 2
  [ -z "${GATE_STEPS:-}" ] || [[ " $GATE_STEPS " == *" $name "* ]] || return 0
  "$@" > "$L/$log.log" 2>&1
  local rc=$?
  echo "$name $rc"
  # memory and disk after the step, so a runner lost mid-job still shows where it was heading (Linux CI only)
  command -v free > /dev/null && echo "  [res] $(free -m | awk 'NR==2{printf "mem %s/%sMB ", $3, $2} NR==3{printf "swap %sMB ", $3}')disk free $(df -h . | awk 'NR==2{print $4}')"
  [ $rc -eq 0 ] || { FAILED=1; FAILED_STEPS="${FAILED_STEPS:+$FAILED_STEPS }$name"; step_summary "$L/$log.log"; }
}
