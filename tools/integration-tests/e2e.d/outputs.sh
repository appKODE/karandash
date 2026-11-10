# Reporter outputs (JUnit/HTML files, webhook, Slack, email) and the HTTP remote cache (`cache push`).
# Needs: python3 (the SMTP sink, the CAS blobs). Mocks: mock-server/mock_cachehook.py, mock-server/smtp_sink.py.

cat >"$F/out-files.yaml" <<'EOC'
rules:
  title-format:
    pattern: "^fix: .+"
reporters:
  junit:
    output: out-results.xml
  html:
    output: out-report.html
EOC
cat >"$F/out-webhook.yaml" <<EOC
allow_localhost: true
rules:
  title-format:
    pattern: "^fix: .+"
reporters:
  webhook:
    url: "http://localhost:$PORT/hook/results"
    auth-token: hook-secret
    headers:
      X-Source: karandash
EOC
cat >"$F/out-webhook-noauth.yaml" <<EOC
allow_localhost: true
rules:
  title-format:
    pattern: "^fix: .+"
reporters:
  webhook:
    url: "http://localhost:$PORT/hook/results"
EOC
# Without allow_localhost the webhook SSRF guard refuses the local URL
sed '/^allow_localhost/d' "$F/out-webhook.yaml" >"$F/out-webhook-ssrf.yaml"
# Slack: under allow_localhost a loopback URL stands in for hooks.slack.com; without it only hooks.slack.com passes
cat >"$F/out-slack.yaml" <<EOC
allow_localhost: true
rules:
  title-format:
    pattern: "^fix: .+"
reporters:
  slack:
    webhook: "http://localhost:$PORT/hook/slack"
EOC
sed '/^allow_localhost/d' "$F/out-slack.yaml" >"$F/out-slack-guard.yaml"

# out_email_config <port> <host> <starttls>: an email reporter config; no username, so no SMTP AUTH
out_email_config() {
    cat <<EOC
rules:
  title-format:
    pattern: "^fix: .+"
reporters:
  email:
    host: $2
    port: $1
    from: bot@example.com
    to: team@example.com
    starttls: $3
EOC
}

# out_smtp_has <id> <log> <regex>: the SMTP sink saw a matching line (envelope, header or body)
out_smtp_has() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if grep -Eq -- "$3" "$2"; then
        record_pass "$1"
    else
        record_fail "$1" "SMTP sink saw no line matching /$3/: $(head -c 400 "$2" | tr '\n' ' ')"
    fi
}

# out_no_requests <id>: the mock received no request at all during the last scenario
out_no_requests() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if [[ ! -s "$REQUESTS" ]]; then
        record_pass "$1"
    else
        record_fail "$1" "mock saw requests: $(tr '\n' ' ' <"$REQUESTS")"
    fi
}

# out_seed_cas: the runtime's local CAS holds exactly two blobs, "cache blob one" and "cache blob two"
out_seed_cas() {
    local cas="$WORK/home-$RUNTIME/.karandash/cache/cas"
    rm -rf "$cas"
    python3 - "$cas" <<'EOP'
import hashlib, os, sys
for text in ("cache blob one", "cache blob two"):
    d = hashlib.sha256(text.encode()).hexdigest()
    os.makedirs(os.path.join(sys.argv[1], d[:2]), exist_ok=True)
    open(os.path.join(sys.argv[1], d[:2], d[2:]), "w").write(text)
EOP
}

scenarios_outputs() {
    # JUnit XML and HTML: the reporters config writes them through the filesystem output
    rm -f "$F/out-results.xml" "$F/out-report.html"
    scenario out-junit-html 1 'Report written to out-results.xml' -- validate --mr mr.json --config out-files.yaml
    file_has out-junit-file out-results.xml '<testsuites name="karandash" tests="1" failures="1"'
    file_has out-junit-failure out-results.xml '<failure message="MR title does not match'
    file_has out-html-file out-report.html '<!DOCTYPE html>'
    file_has out-html-finding out-report.html 'titleFormat'

    # Webhook: JSON POST with bearer token and custom header; the mock answers 401 without them
    scenario out-webhook 1 'Validation FAILED' -- validate --mr mr.json --config out-webhook.yaml
    requests out-webhook-post 'POST /hook/results'
    requests out-webhook-auth 'HOOK-AUTH ok /hook/results'
    bodies out-webhook-body 'POST /hook/results \{'
    # the JSON report is pretty-printed, so the body log holds it over several lines
    bodies out-webhook-body-failed '"passed": false'
    bodies out-webhook-body-rule '"rule": "titleFormat"'
    scenario out-webhook-noauth 1 'HTTP 401' -- validate --mr mr.json --config out-webhook-noauth.yaml
    scenario out-webhook-ssrf 1 'must use HTTPS' -- validate --mr mr.json --config out-webhook-ssrf.yaml
    out_no_requests out-webhook-ssrf-blocked

    # Slack: Block Kit JSON POSTed to the loopback stand-in under allow_localhost
    scenario out-slack 1 'Validation FAILED' -- validate --mr mr.json --config out-slack.yaml
    requests out-slack-post 'POST /hook/slack'
    bodies out-slack-body 'POST /hook/slack .*"blocks"'
    # without allow_localhost a non-hooks.slack.com URL is refused before any request leaves the CLI
    scenario out-slack-guard 1 'hooks.slack.com' -- validate --mr mr.json --config out-slack-guard.yaml
    out_no_requests out-slack-no-post

    # Email: a plaintext loopback relay (`starttls: false`, no AUTH) receives the whole message
    if needs out-email python3; then
        local smtp_port smtp_log="$WORK/smtp-$RUNTIME.log" smtp_pid
        smtp_port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
        out_email_config "$smtp_port" 127.0.0.1 false >"$F/out-email.yaml"
        out_email_config "$smtp_port" 127.0.0.1 true >"$F/out-email-tls.yaml"
        out_email_config "$smtp_port" smtp.invalid false >"$F/out-email-remote.yaml"
        python3 "$TEST_DIR/mock-server/smtp_sink.py" "$smtp_port" "$smtp_log" &
        smtp_pid=$!
        python3 -c 'import time; time.sleep(1)'

        : >"$smtp_log"
        scenario out-email 1 'Validation FAILED' -- validate --mr mr.json --config out-email.yaml
        out_smtp_has out-email-envelope "$smtp_log" '^MAIL FROM:<bot@example.com>'
        out_smtp_has out-email-rcpt "$smtp_log" '^RCPT TO:<team@example.com>'
        out_smtp_has out-email-subject "$smtp_log" '^Subject: =\?UTF-8\?[BQ]\?'
        out_smtp_has out-email-body "$smtp_log" 'titleFormat'

        # default `starttls: true`: a relay that offers no STARTTLS gets no message
        : >"$smtp_log"
        scenario out-email-tls-required 1 'Validation FAILED' -- validate --mr mr.json --config out-email-tls.yaml
        if [[ -z "$ONLY" || out-email-tls-no-mail =~ $ONLY ]]; then
            if grep -q '^EHLO' "$smtp_log" && ! grep -q '^MAIL FROM' "$smtp_log"; then
                record_pass out-email-tls-no-mail
            else
                record_fail out-email-tls-no-mail "expected EHLO and no MAIL FROM: $(tr '\n' ' ' <"$smtp_log")"
            fi
        fi

        # plaintext to a non-loopback host is refused before connecting
        scenario out-email-plaintext-remote 1 'only allowed for a loopback SMTP host' -- \
            validate --mr mr.json --config out-email-remote.yaml

        kill "$smtp_pid" 2>/dev/null
        wait "$smtp_pid" 2>/dev/null
    fi

    # Remote cache over HTTP: `cache push` PUTs every local CAS blob, the mock checks each body hashes to its path
    if needs cache-remote-push python3; then
        local cachebk="http://localhost:$PORT/cachebk/$RUNTIME"
        out_seed_cas
        scenario cache-remote-push 0 'Cache push complete: 2 pushed, 0 skipped, 0 failed' -- \
            cache push --backend-type http --backend-url "$cachebk" --allow-localhost
        # a refused PUT (non-2xx) counts as failed, so "2 pushed" means the mock answered 2xx to both
        local text digest
        for text in "cache blob one" "cache blob two"; do
            digest="$(printf '%s' "$text" | shasum -a 256 | cut -c1-64)"
            requests "cache-remote-put-${text##* }" "PUT /cachebk/$RUNTIME/cas/${digest:0:2}/${digest:2}"
        done
        bodies cache-remote-body 'cache blob (one|two)'
        # the mock stores only a body that hashes to its path; a second push finds both by HEAD and skips them
        scenario cache-remote-stored 0 'Cache push complete: 0 pushed, 2 skipped, 0 failed' -- \
            cache push --backend-type http --backend-url "$cachebk" --allow-localhost
        # a backend that answers 400 to every PUT: each blob counts as failed and the push exits 6
        scenario cache-remote-refused 6 'Cache push complete: 0 pushed, 0 skipped, 2 failed' -- \
            cache push --backend-type http --backend-url "http://localhost:$PORT/cachebk-refuse" --allow-localhost
        # without the flag the loopback backend is refused per blob and nothing is sent
        scenario cache-remote-ssrf 6 '0 pushed, 0 skipped, 2 failed' -- cache push --backend-type http --backend-url "$cachebk"
        out_no_requests cache-remote-ssrf-blocked
    fi

    # Remote cache on S3 (path-style mock, one bucket per runtime): push PUTs both blobs, a second push finds them
    # by HEAD and skips them. The mock stores only a body that hashes to its key, so 2 skipped = 2 verified blobs.
    if needs cache-remote-s3-push python3; then
        local s3="http://localhost:$PORT/s3/cache-$RUNTIME"
        out_seed_cas
        scenario cache-remote-s3-push 0 'Cache push complete: 2 pushed, 0 skipped, 0 failed' -- \
            cache push --backend-type s3 --backend-url "$s3" --allow-localhost
        requests cache-remote-s3-put "PUT /s3/cache-$RUNTIME/cas/[0-9a-f]{2}/[0-9a-f]{62}"
        scenario cache-remote-s3-hit 0 'Cache push complete: 0 pushed, 2 skipped, 0 failed' -- \
            cache push --backend-type s3 --backend-url "$s3" --allow-localhost
        requests cache-remote-s3-head "HEAD /s3/cache-$RUNTIME/cas/[0-9a-f]{2}/[0-9a-f]{62}"
    fi

    # Signed S3: a `signed-*` bucket refuses unsigned requests and checks each SigV4 signature against the mock key.
    # Backend, URL and credentials come from cache.remote in the --config file (no --backend-* flags).
    if needs cache-remote-s3-signed python3; then
        local signed="http://localhost:$PORT/s3/signed-$RUNTIME"
        out_seed_cas
        cat >"$F/s3-signed.yaml" <<EOF
allow-localhost: true
cache:
  remote:
    backend: s3
    url: "$signed"
    region: eu-west-1
    access-key-id: AKIDKARANDASHTEST
    secret-access-key: karandash-mock-secret
EOF
        # without credentials the bucket answers 403 to every PUT, so both blobs fail
        scenario cache-remote-s3-unsigned 6 'Cache push complete: 0 pushed, 0 skipped, 2 failed' -- \
            cache push --backend-type s3 --backend-url "$signed" --allow-localhost
        scenario cache-remote-s3-signed 0 'Cache push complete: 2 pushed, 0 skipped, 0 failed' -- \
            cache push --config "$F/s3-signed.yaml"
        scenario cache-remote-s3-signed-hit 0 'Cache push complete: 0 pushed, 2 skipped, 0 failed' -- \
            cache push --config "$F/s3-signed.yaml"
    fi
}
