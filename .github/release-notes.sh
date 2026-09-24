#!/usr/bin/env bash
# Prints the release notes of a version: how to pull its image, then its section of CHANGELOG.md.
# Fails when the changelog has no section for the version, so that a release cannot go out without one.
set -euo pipefail

version=${1:?usage: release-notes.sh <version> <image>}
image=${2:?usage: release-notes.sh <version> <image>}

changes=$(awk -v heading="## [$version]" '
  index($0, heading) == 1 { found = 1; next }
  found && /^## \[/ { exit }
  found { print }' CHANGELOG.md)
if [ -z "$(echo "$changes" | tr -d '[:space:]')" ]; then
  echo "CHANGELOG.md has no section for $version; add one before tagging." >&2
  exit 1
fi

echo '```bash'
echo "docker pull $image:$version"
echo '```'
echo "$changes"
