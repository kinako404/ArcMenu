#!/usr/bin/env bash
# ProtocolLib 5.5.0-SNAPSHOT（支持 26.3）没有发布到 Maven 仓库：下载 GitHub「Development Build」（tag dev-build）
# 附件 ProtocolLib.jar，装进本地 Maven 仓库，供 pom.xml 的 protocollib.version=5.5.0-SNAPSHOT 使用。
# 用法：tinkers-test/install-protocollib-dev.sh [已下载的 ProtocolLib.jar]
set -euo pipefail
JAR=${1:-}
if [ -z "$JAR" ]; then
	DIR=$(mktemp -d)
	gh release download dev-build -R dmulloy2/ProtocolLib -p ProtocolLib.jar -D "$DIR"
	JAR=$DIR/ProtocolLib.jar
fi
mvn --batch-mode --no-transfer-progress install:install-file -Dfile="$JAR" \
	-DgroupId=net.dmulloy2 -DartifactId=ProtocolLib -Dversion=5.5.0-SNAPSHOT -Dpackaging=jar
