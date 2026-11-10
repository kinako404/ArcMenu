#!/usr/bin/env bash
# Installs a ProtocolLib build that is not on a Maven repository (the GitHub "Development Build", release tag
# dev-build, which carries Minecraft 26.x support before a release) into the local Maven repository, so that
# -Dprotocollib.version=5.5.0-SNAPSHOT resolves to it instead of an older snapshot.
#
# Usage: scripts/install-protocollib-dev.sh [-j JAR] [-v VERSION] [-t TAG] [-r OWNER/REPO] [-- extra mvn args]
#   -j JAR      install this already downloaded jar instead of downloading
#   -v VERSION  Maven version to install as (default 5.5.0-SNAPSHOT)
#   -t TAG      GitHub release tag to download from (default dev-build)
#   -r REPO     GitHub repository (default dmulloy2/ProtocolLib)
# Environment: MVN (default mvn), GH_TOKEN (optional; gh is used when available, otherwise curl).
set -euo pipefail

JAR=
VERSION=5.5.0-SNAPSHOT
TAG=dev-build
REPO=dmulloy2/ProtocolLib
ASSET=ProtocolLib.jar
MVN=${MVN:-mvn}

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
	case "$1" in
		-j) JAR=$2; shift 2 ;;
		-v) VERSION=$2; shift 2 ;;
		-t) TAG=$2; shift 2 ;;
		-r) REPO=$2; shift 2 ;;
		-h|--help) usage ;;
		--) shift; break ;;
		*) echo "unknown argument: $1" >&2; usage 2 ;;
	esac
done

if [ -z "$JAR" ]; then
	DIR=$(mktemp -d)
	trap 'rm -rf "$DIR"' EXIT
	if command -v gh >/dev/null 2>&1 && { [ -n "${GH_TOKEN:-}" ] || gh auth status >/dev/null 2>&1; }; then
		gh release download "$TAG" -R "$REPO" -p "$ASSET" -D "$DIR"
	else
		curl -fsSL --retry 3 -o "$DIR/$ASSET" "https://github.com/$REPO/releases/download/$TAG/$ASSET"
	fi
	JAR=$DIR/$ASSET
fi
[ -s "$JAR" ] || { echo "missing jar: $JAR" >&2; exit 1; }

DESCRIPTOR=$(unzip -p "$JAR" paper-plugin.yml 2>/dev/null || unzip -p "$JAR" plugin.yml 2>/dev/null || true)
BUILT=$(printf '%s\n' "$DESCRIPTOR" | sed -n 's/^version: *["'\'']\{0,1\}\([^"'\'']*\).*/\1/p' | head -1)
echo "Installing $JAR (${BUILT:-unknown build}, sha256 $(sha256sum "$JAR" | cut -c1-16)…) as net.dmulloy2:ProtocolLib:$VERSION"

"$MVN" --batch-mode --no-transfer-progress "$@" install:install-file -Dfile="$JAR" \
	-DgroupId=net.dmulloy2 -DartifactId=ProtocolLib -Dversion="$VERSION" -Dpackaging=jar -DgeneratePom=true
