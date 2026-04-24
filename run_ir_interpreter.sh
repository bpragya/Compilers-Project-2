#!/bin/bash
# Builds the project then runs IRInterpreter with the provided arguments.
# Usage: run_ir_interpreter.sh <input-ir-file>
set -e
cd "$(dirname "$0")"
./build.sh
java -cp build IRInterpreter "$@"
