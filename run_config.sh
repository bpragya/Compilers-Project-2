#!/bin/bash
# Builds the project then runs Main with the provided arguments.
# Usage: run_config.sh <input-ir-file> <output-s-file>
set -e
cd "$(dirname "$0")"
./build.sh
java -cp build Main "$@"
