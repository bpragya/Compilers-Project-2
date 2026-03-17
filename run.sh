#!/bin/bash

# Usage: run.sh <path-to-input-ir-file> <path-to-output-ir-file>
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <input-ir-file> <output-ir-file>" >&2
  exit 1
fi

INPUT_IR="$1"
OUTPUT_IR="$2"

java -cp build Main "$INPUT_IR" > "$OUTPUT_IR"