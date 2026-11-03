#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p out
find src/main/java -name '*.java' -print > out/sources.txt
javac -d out @out/sources.txt
if [ -d src/main/resources ]; then cp -R src/main/resources/. out/; fi
jar --create --file out/egoncode.jar --main-class dev.egoncode.Main -C out dev -C out bunny-idle.gif -C out bunny-run.gif -C out bunny-sleep.gif
