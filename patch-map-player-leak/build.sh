#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MC_DIR="${MC_DIR:?Set MC_DIR to your local Fabric server directory}"
SERVER_JAR="${SERVER_JAR:-$MC_DIR/versions/26.3/server-26.3.jar}"
MIXIN_JAR="${MIXIN_JAR:-$MC_DIR/libraries/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar}"
LOADER_JAR="${LOADER_JAR:-$MC_DIR/libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar}"

rm -rf build
mkdir -p build/deps build/classes build/libs
(cd build/deps && jar xf "$LOADER_JAR" META-INF/jars/mixinextras-fabric-0.5.5.jar)
CP="$SERVER_JAR:$MIXIN_JAR:build/deps/META-INF/jars/mixinextras-fabric-0.5.5.jar:$(find "$MC_DIR/libraries" -name '*.jar' -printf '%p:' | tr -d '\n')"
javac -proc:none --release 25 -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
cp ../LICENSE build/classes/LICENSE_patch-map-player-leak
jar --create --file build/libs/patch-map-player-leak-1.0.0.jar -C build/classes .
