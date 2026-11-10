#!/usr/bin/env bash
# Bash counterpart of Test-PaperVersionMatrix.ps1: compiles and unit-tests ArcMenu against each supported Paper API,
# then checks JOML linkage (scripts/check-joml-compat.py) on the compiled classes.
#
# Usage: scripts/test-paper-version-matrix.sh [-k] [-o] [MINECRAFT_VERSION ...]
#   -k  keep going after a failing entry (default: stop at the first failure, like the PowerShell script)
#   -o  pass --offline to Maven
#   MINECRAFT_VERSION  only run these entries (e.g. 1.21.1 26.3); default: the whole matrix
# Environment: ARCMENU_JDK21, ARCMENU_JDK25 (JDK homes, required), MVN (default mvn),
#   ARCMENU_MATRIX_LOGS (log directory, default target/version-matrix).
# 26.x entries use ProtocolLib 5.5.0-SNAPSHOT; install the dev build first with scripts/install-protocollib-dev.sh.
set -uo pipefail

MATRIX=(
	"1.21.1   1.21.1-R0.1-SNAPSHOT    5.4.0          21"
	"1.21.4   1.21.4-R0.1-SNAPSHOT    5.4.0          21"
	"1.21.6   1.21.6-R0.1-SNAPSHOT    5.4.0          21"
	"1.21.11  1.21.11-R0.1-SNAPSHOT   5.4.0          21"
	"26.1.2   26.1.2.build.74-stable  5.5.0-SNAPSHOT 25"
	"26.2     26.2.build.135-stable   5.5.0-SNAPSHOT 25"
	"26.3     26.3.build.246-stable   5.5.0-SNAPSHOT 25"
)

KEEP_GOING=0
OFFLINE=()
while getopts "koh" opt; do
	case $opt in
		k) KEEP_GOING=1 ;;
		o) OFFLINE=(--offline) ;;
		*) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
	esac
done
shift $((OPTIND - 1))
ONLY=("$@")

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MVN=${MVN:-mvn}
LOGS=${ARCMENU_MATRIX_LOGS:-$ROOT/target/version-matrix}
mkdir -p "$LOGS"
declare -A JDKS=([21]=${ARCMENU_JDK21:-} [25]=${ARCMENU_JDK25:-})

selected() {
	[ ${#ONLY[@]} -eq 0 ] && return 0
	local v
	for v in "${ONLY[@]}"; do [ "$v" = "$1" ] && return 0; done
	return 1
}

RESULTS=()
FAILED=0
RAN=0
for entry in "${MATRIX[@]}"; do
	read -r MC PAPER PLIB JAVA <<<"$entry"
	selected "$MC" || continue
	JDK=${JDKS[$JAVA]}
	if [ -z "$JDK" ] || [ ! -x "$JDK/bin/java" ]; then
		echo "Set ARCMENU_JDK$JAVA to a JDK $JAVA home before running the version matrix." >&2
		exit 2
	fi
	LOG=$LOGS/$MC.log
	echo "==> Minecraft $MC: paper-api $PAPER, ProtocolLib $PLIB, $("$JDK/bin/java" -version 2>&1 | head -1)"
	START=$SECONDS
	(cd "$ROOT" && JAVA_HOME=$JDK PATH=$JDK/bin:$PATH "$MVN" -B -ntp "${OFFLINE[@]}" \
		"-Dpaper.version=$PAPER" "-Dprotocollib.version=$PLIB" clean test) >"$LOG" 2>&1
	MVN_RC=$?
	ABI=-
	if [ $MVN_RC -eq 0 ]; then
		if JAVA_HOME=$JDK python3 "$ROOT/scripts/check-joml-compat.py" >>"$LOG" 2>&1; then ABI=OK; else ABI=FAIL; fi
	fi
	TESTS=$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOG" |
		awk -F'[:,]' '{r+=$2; f+=$4+$6; s+=$8} END {if (NR) printf "%d run, %d failed, %d skipped", r, f, s; else print "no tests"}')
	RESULT=PASS
	{ [ $MVN_RC -ne 0 ] || [ "$ABI" != OK ]; } && RESULT=FAIL
	if [ $RESULT = FAIL ]; then
		FAILED=1
		grep -E '^\[ERROR\] (file:|.*Test\.|Tests run:.*FAIL)|^e: ' "$LOG" | sort -u | head -5 | sed 's/^/    /'
		grep -A3 '^JOML .*unresolved' "$LOG" | head -4 | sed 's/^/    /'
	fi
	RAN=$((RAN + 1))
	RESULTS+=("$(printf '%-8s %-24s %-15s %-4s %-5s %-5s %-36s %5ss' "$MC" "$PAPER" "$PLIB" "$JAVA" "$RESULT" "$ABI" "$TESTS" $((SECONDS - START)))")
	[ $RESULT = FAIL ] && [ $KEEP_GOING -eq 0 ] && break
done

printf '%-8s %-24s %-15s %-4s %-5s %-5s %-36s %6s\n' Minecraft PaperApi ProtocolLib JDK Build JOML Tests Time
printf '%s\n' "${RESULTS[@]}"
echo "Logs: $LOGS"
[ $RAN -eq 0 ] && { echo "no matrix entry selected" >&2; exit 2; }
exit $FAILED
