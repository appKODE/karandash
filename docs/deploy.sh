#!/bin/bash
# Build the docs site, then serve it locally.
#   docs/deploy.sh                 build + serve (local preview)
#   docs/deploy.sh build [dir]     build only, into dir (default: site); used by the CI pages job
cd "$(dirname "$0")/.." || exit 1
if [ "${1:-}" = "build" ]; then
  exec mkdocs build --strict -d "${2:-site}"
fi
mkdocs build
mkdocs serve
