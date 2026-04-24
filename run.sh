#!/bin/bash

# Usage: run.sh <path-to-input-ir-file> <path-to-output-s-file> [--naive|--greedy]
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <input-ir-file> <output-s-file> [--naive|--greedy]" >&2
  exit 1
fi

INPUT_IR="$1"
OUTPUT_S="$2"
ALLOCATOR_FLAG="$3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/build.sh" || exit 1

java -cp build Main "$INPUT_IR" "$OUTPUT_S" "$ALLOCATOR_FLAG" || exit 1

java -cp build mips.MIPSInterpreter "$OUTPUT_S"
