#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
java -version
mvn -B verify "$@"
