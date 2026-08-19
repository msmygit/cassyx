#!/usr/bin/env bash
# =============================================================================
# Release version guard.
#
# Asserts that a `v*` git tag names exactly the version the artefacts will be
# built from, i.e. <project>/<version> in backend/pom.xml.
#
# Why this is a correctness gate and not a tidiness one: the reactor version is
# what the running app reports from GET /api/health, and - per plan §9.5 - it is
# also what licence *scope* is checked against. A key sold for major 1 runs any
# 1.x and yields UPGRADE_REQUIRED on 2.x. So tagging v2.0.0 while the pom still
# says 1.0.0 publishes an image that tells every customer it is v1: the tag
# promises an upgrade the binary does not believe it is, and the licence gate
# makes the wrong decision for every user of that image. Cheaper to fail here.
#
# The version is PARSED, not grepped. backend/pom.xml contains ~40 <version>
# elements (dependencies, plugins, managed BOMs); only the one that is a direct
# child of the root <project> is the reactor version, and a line-oriented grep
# cannot express "direct child". ElementTree with the POM namespace can.
#
# Usage:
#   scripts/release-version.sh                 print the pom version, exit 0
#   scripts/release-version.sh v1.2.3          assert the tag matches, then print
#
# Accepts the tag with or without its leading `v`.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="${CASSYX_POM:-$ROOT/backend/pom.xml}"
TAG="${1:-}"

RED=$'\033[31m'; GREEN=$'\033[32m'; OFF=$'\033[0m'

[ -f "$POM" ] || { printf '%s✗ %s does not exist.%s\n' "$RED" "$POM" "$OFF" >&2; exit 1; }

# python3 is present on every GitHub-hosted runner and on macOS/most Linux dev
# boxes. It is used only to READ xml here; nothing about the product build
# depends on a host toolchain (plan §2.2), which still happens in containers.
command -v python3 >/dev/null 2>&1 || {
  printf '%s✗ python3 is required to parse the pom.%s\n' "$RED" "$OFF" >&2; exit 1; }

POM_VERSION="$(python3 - "$POM" <<'PY'
import sys, xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()

# Direct child of <project> only. A reactor root has no <parent>, so an absent
# <version> here is a real defect rather than something to inherit.
node = root.find("m:version", NS)
if node is None:                      # tolerate a pom written without the namespace
    node = root.find("version")
if node is None or not (node.text or "").strip():
    sys.exit("no <version> found as a direct child of <project>")

version = node.text.strip()
if version.startswith("${"):
    sys.exit(f"<version> is an unresolved property ({version}); cannot verify a tag against it")
print(version)
PY
)" || { printf '%s✗ could not read the reactor version from %s%s\n' "$RED" "$POM" "$OFF" >&2; exit 1; }

if [ -z "$TAG" ]; then
  printf '%s\n' "$POM_VERSION"
  exit 0
fi

TAG_VERSION="${TAG#refs/tags/}"
TAG_VERSION="${TAG_VERSION#v}"

if [ "$TAG_VERSION" != "$POM_VERSION" ]; then
  printf '\n%s✗ RELEASE BLOCKED - tag/version mismatch%s\n\n' "$RED" "$OFF" >&2
  printf '  git tag        : %s (version %s)\n' "$TAG" "$TAG_VERSION" >&2
  printf '  backend/pom.xml: %s\n\n' "$POM_VERSION" >&2
  printf '  The published image would report %s from /api/health and scope licences\n' "$POM_VERSION" >&2
  printf '  against major %s (plan §9.5), while the tag promises %s. That is a\n' "${POM_VERSION%%.*}" "$TAG_VERSION" >&2
  printf '  licensing correctness bug, not a cosmetic one.\n\n' >&2
  printf '  Fix: set <version>%s</version> in backend/pom.xml (and every module\n' "$TAG_VERSION" >&2
  printf '  parent block), commit, delete the tag, and re-tag the new commit.\n\n' >&2
  exit 1
fi

# Diagnostics on stderr so stdout stays a clean, capturable version string:
#   VERSION="$(scripts/release-version.sh "$GITHUB_REF")"
printf '%s✓ tag %s matches backend/pom.xml (%s)%s\n' "$GREEN" "$TAG" "$POM_VERSION" "$OFF" >&2
printf '%s\n' "$POM_VERSION"
