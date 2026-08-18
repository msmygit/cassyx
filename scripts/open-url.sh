#!/usr/bin/env bash
# Open a URL in the user's browser. macOS / Linux / WSL / Git-Bash parity.
# Never fails the build — printing the URL is an acceptable outcome.
set -uo pipefail
URL="${1:?usage: open-url.sh <url>}"
printf '\n\033[32m➜ cassyx is up:\033[0m \033[1m%s\033[0m\n\n' "$URL"
[ -n "${CASSYX_NO_OPEN:-}" ] && exit 0
[ -n "${CI:-}" ] && exit 0
if   command -v open        >/dev/null 2>&1; then open "$URL"
elif command -v wslview     >/dev/null 2>&1; then wslview "$URL"
elif command -v xdg-open    >/dev/null 2>&1; then xdg-open "$URL"
elif command -v powershell.exe >/dev/null 2>&1; then powershell.exe -NoProfile start "$URL"
elif command -v start       >/dev/null 2>&1; then start "$URL"
fi >/dev/null 2>&1 || true
exit 0
