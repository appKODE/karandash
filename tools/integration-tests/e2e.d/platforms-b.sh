# Platforms B: Azure DevOps, AWS CodeCommit, Jenkins (CI detection) and local git. AWS runs against mock_aws.py
# through the endpoint / sts-endpoint overrides; the mock verifies each SigV4 signature against the fixture secret.

ado_config() {
    cat <<EOT
allow_localhost: true
platforms:
  ${2:-azureDevOps}:
    url: "http://localhost:$PORT/ado"
    organization: "org"
    token: "$1"
rules:
  title-format:
    pattern: "^feat: .+"
EOT
}
ado_config ado-pat >"$F/ado.yaml"
ado_config wrong-pat >"$F/ado-wrong.yaml"
mkdir -p "$F/ado-doctor/.karandash" "$F/ado-doctor-wrong/.karandash"
ado_config ado-pat >"$F/ado-doctor/.karandash/config.yaml"
ado_config wrong-pat >"$F/ado-doctor-wrong/.karandash/config.yaml"
# The kebab-case block name the docs also accept (normalized to azureDevops, matched to the azureDevOps service)
ado_config ado-pat azure-devops >"$F/ado-kebab.yaml"
mkdir -p "$F/ado-doctor-kebab/.karandash"
ado_config ado-pat azure-devops >"$F/ado-doctor-kebab/.karandash/config.yaml"

# AWS CodeCommit: fixture-only credentials (mock_aws.py ACCESS_KEY / SECRET); $1 = secret, $2 = allow_localhost
aws_config() {
    cat <<EOT
allow_localhost: ${2:-true}
platforms:
  aws-codecommit:
    region: "eu-west-1"
    endpoint: "http://localhost:$PORT/aws"
    sts-endpoint: "http://localhost:$PORT/aws-sts"
    access-key-id: "AKIDKARANDASHTEST"
    secret-access-key: "$1"
rules:
  title-format:
    pattern: "^feat: .+"
EOT
}
aws_config karandash-mock-secret >"$F/aws.yaml"
aws_config wrong-secret >"$F/aws-wrong.yaml"
aws_config karandash-mock-secret false >"$F/aws-no-localhost.yaml"
mkdir -p "$F/aws-doctor/.karandash" "$F/aws-doctor-wrong/.karandash"
aws_config karandash-mock-secret >"$F/aws-doctor/.karandash/config.yaml"
aws_config wrong-secret >"$F/aws-doctor-wrong/.karandash/config.yaml"

# Jenkins: JENKINS_URL + CHANGE_URL select the platform that fetches the MR (GitLab on the mock here)
JENKINS_CHANGE_URL="http://localhost:$PORT/test/project/-/merge_requests/1"

# Local git: a real repository, main plus a feature branch with one commit
LG="$WORK/localgit"
if command -v git >/dev/null 2>&1; then
    mkdir -p "$LG"
    (
        cd "$LG" && git init -q -b main \
            && git -c user.name=t -c user.email=t@example.com commit -q --allow-empty -m "chore: init" \
            && git checkout -q -b feature/x && echo hi >a.txt && git add a.txt \
            && git -c user.name=t -c user.email=t@example.com commit -q -m "feat: add a"
    ) >/dev/null 2>&1
    cat >"$LG/pass.yaml" <<'EOT'
rules:
  title-format:
    pattern: "^feat: .+"
reporters:
  console: true
EOT
    sed 's/\^feat/^fix/' "$LG/pass.yaml" >"$LG/fail.yaml"
fi

scenarios_platforms_b() {
    # Azure DevOps
    scenario ado-validate 0 'Comment created' -- validate --platform azure-devops --project org/proj/repo --mr-id 1 --config ado.yaml
    requests ado-fetch 'GET /ado/org/proj/_apis/git/repositories/repo/pullrequests/1'
    requests ado-changes 'GET /ado/org/proj/_apis/git/repositories/repo/[Pp]ull[Rr]equests/1/iterations/1/changes'
    requests ado-comment 'POST /ado/org/proj/_apis/git/repositories/repo/pullrequests/1/threads'
    scenario ado-wrong-token 3 '401|authentication|credentials|Unauthorized' -- validate --platform azure-devops --project org/proj/repo --mr-id 1 --config ado-wrong.yaml
    scenario ado-validate-kebab 0 'Comment created' -- validate --platform azure-devops --project org/proj/repo --mr-id 1 --config ado-kebab.yaml
    SCEN_DIR="$F/ado-doctor"
    scenario ado-doctor 0 'azureDevOps: credentials accepted' -- doctor
    requests ado-doctor-request 'GET /ado/org/_apis/connectionData'
    SCEN_DIR="$F/ado-doctor-wrong"
    scenario ado-doctor-wrong 1 'azureDevOps: credentials rejected' -- doctor
    SCEN_DIR="$F/ado-doctor-kebab"
    scenario ado-doctor-kebab 0 'azureDevops: credentials accepted' -- doctor
    SCEN_DIR=""

    # AWS CodeCommit
    scenario aws-validate 0 'Comment created' -- validate --platform awscodecommit --project repo --mr-id 1 --config aws.yaml
    requests aws-fetch 'AWS GetPullRequest'
    requests aws-commits 'AWS GetCommit'
    requests aws-diff 'AWS GetBlob'
    requests aws-comment 'AWS PostCommentForPullRequest'
    scenario aws-wrong-creds 3 '403|security token' -- validate --platform awscodecommit --project repo --mr-id 1 --config aws-wrong.yaml
    scenario aws-endpoint-ssrf 3 'Base URL must use HTTPS|localhost|SSRF' -- validate --platform awscodecommit --project repo --mr-id 1 --config aws-no-localhost.yaml
    SCEN_DIR="$F/aws-doctor"
    scenario aws-doctor 0 'awsCodecommit: credentials accepted' -- doctor
    SCEN_DIR="$F/aws-doctor-wrong"
    scenario aws-doctor-wrong 1 'awsCodecommit: credentials rejected' -- doctor
    SCEN_DIR=""

    # Jenkins: CI detection picks GitLab from CHANGE_URL; GitLab fetches the MR
    SCEN_ENV=("JENKINS_URL=http://localhost:$PORT/jenkins/" "CHANGE_URL=$JENKINS_CHANGE_URL")
    scenario jenkins-validate 0 '' -- validate --config live-pass.yaml
    requests jenkins-fetch 'GET /api/v4/projects/[^ ]+/merge_requests/1'
    SCEN_ENV=("JENKINS_URL=http://localhost:$PORT/jenkins/")
    scenario jenkins-no-change-url 2 'Must specify either --mr' -- validate --config live-pass.yaml
    SCEN_ENV=()

    # Local git
    if needs localgit-validate git && [[ -d "$LG/.git" ]]; then
        SCEN_DIR="$LG"
        scenario localgit-validate 0 'No issues found' -- validate-local --config pass.yaml
        scenario localgit-validate-fail 1 'Validation FAILED' -- validate-local --config fail.yaml
        scenario localgit-platform-flag 0 'No issues found' -- validate --platform local-git --project main --mr-id local --config pass.yaml
        scenario localgit-target-missing 1 '' -- validate-local --target nosuchbranch --config pass.yaml
        SCEN_DIR=""
    fi
}
