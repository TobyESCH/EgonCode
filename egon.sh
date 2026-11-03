#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f out/egoncode.jar ]; then ./build.sh; fi
exec java -jar out/egoncode.jar "$@"
