# Gerrit, Gitea and Bitbucket against the mock (mock_gerrit.py, mock_gitea.py, mock_bitbucket.py): live validate,
# wrong credentials, doctor. Bitbucket runs with an app password (Basic username:app-pass) and an access token (Bearer).

mkdir -p "$F/gerrit-doctor/.karandash" "$F/gitea-doctor/.karandash" "$F/gitea-doctor-wrong/.karandash"
cat >"$F/gerrit.yaml" <<EOF2
allow_localhost: true
platforms:
  gerrit:
    url: "http://localhost:$PORT/gerrit"
    username: alice
    token: http-pass
rules:
  title-format:
    pattern: "^feat: .+"
EOF2
sed 's/token: http-pass/token: wrong-pass/' "$F/gerrit.yaml" >"$F/gerrit-wrong.yaml"
cp "$F/gerrit.yaml" "$F/gerrit-doctor/.karandash/config.yaml"
mkdir -p "$F/gerrit-doctor-wrong/.karandash"
cp "$F/gerrit-wrong.yaml" "$F/gerrit-doctor-wrong/.karandash/config.yaml"
cat >"$F/gitea.yaml" <<EOF2
allow_localhost: true
platforms:
  gitea:
    url: "http://localhost:$PORT/gitea"
    token: gitea-token
rules:
  title-format:
    pattern: "^feat: .+"
EOF2
sed 's/token: gitea-token/token: wrong-token/' "$F/gitea.yaml" >"$F/gitea-wrong.yaml"
cp "$F/gitea.yaml" "$F/gitea-doctor/.karandash/config.yaml"
cp "$F/gitea-wrong.yaml" "$F/gitea-doctor-wrong/.karandash/config.yaml"
mkdir -p "$F/bb-doctor/.karandash" "$F/bb-doctor-wrong/.karandash"
cat >"$F/bb.yaml" <<EOF2
allow_localhost: true
platforms:
  bitbucket:
    url: "http://localhost:$PORT/bb/2.0"
    username: alice
    token: app-pass
rules:
  title-format:
    pattern: "^feat: .+"
EOF2
sed 's/token: app-pass/token: wrong-pass/' "$F/bb.yaml" >"$F/bb-wrong.yaml"
sed -e '/username: alice/d' -e 's/token: app-pass/token: bb-token/' "$F/bb.yaml" >"$F/bb-bearer.yaml"
cp "$F/bb.yaml" "$F/bb-doctor/.karandash/config.yaml"
cp "$F/bb-wrong.yaml" "$F/bb-doctor-wrong/.karandash/config.yaml"

scenarios_platforms_a() {
    scenario gerrit-validate 0 'Comment created' -- validate --platform gerrit --project proj --mr-id 7 --config gerrit.yaml
    requests gerrit-fetch 'GET /gerrit/a/changes/proj~7$'
    requests gerrit-comment 'POST /gerrit/a/changes/7/revisions/current/review'
    scenario gerrit-wrong-creds 3 'Unauthorized|401' -- validate --platform gerrit --project proj --mr-id 7 --config gerrit-wrong.yaml
    scenario gitea-validate 0 'Comment created' -- validate --platform gitea --project owner/repo --mr-id 1 --config gitea.yaml
    requests gitea-fetch 'GET /gitea/api/v1/repos/owner/repo/pulls/1$'
    requests gitea-comment 'POST /gitea/api/v1/repos/owner/repo/issues/1/comments'
    scenario gitea-wrong-creds 3 '401' -- validate --platform gitea --project owner/repo --mr-id 1 --config gitea-wrong.yaml
    SCEN_DIR="$F/gitea-doctor"
    scenario gitea-doctor 0 'gitea: credentials accepted' -- doctor
    requests gitea-doctor-auth 'GET /gitea/api/v1/user'
    SCEN_DIR="$F/gitea-doctor-wrong"
    scenario gitea-doctor-wrong-creds 1 'gitea: credentials rejected \(HTTP 401/403\)' -- doctor
    SCEN_DIR="$F/gerrit-doctor"
    scenario gerrit-doctor 0 'gerrit: credentials accepted' -- doctor
    requests gerrit-doctor-auth 'GET /gerrit/a/accounts/self'
    SCEN_DIR="$F/gerrit-doctor-wrong"
    scenario gerrit-doctor-wrong-creds 1 'gerrit: credentials rejected \(HTTP 401/403\)' -- doctor
    SCEN_DIR=""
    scenario bb-validate 0 'Comment created' -- validate --platform bitbucket --project ws/repo --mr-id 1 --config bb.yaml
    requests bb-fetch 'GET /bb/2.0/repositories/ws/repo/pullrequests/1$'
    requests bb-comment 'POST /bb/2.0/repositories/ws/repo/pullrequests/1/comments'
    scenario bb-validate-bearer 0 'Comment created' -- validate --platform bitbucket --project ws/repo --mr-id 1 --config bb-bearer.yaml
    scenario bb-wrong-creds 3 '401' -- validate --platform bitbucket --project ws/repo --mr-id 1 --config bb-wrong.yaml
    SCEN_DIR="$F/bb-doctor"
    scenario bb-doctor 0 'bitbucket: credentials accepted' -- doctor
    requests bb-doctor-auth 'GET /bb/2.0/user'
    SCEN_DIR="$F/bb-doctor-wrong"
    scenario bb-doctor-wrong-creds 1 'bitbucket: credentials rejected \(HTTP 401/403\)' -- doctor
    SCEN_DIR=""
}
