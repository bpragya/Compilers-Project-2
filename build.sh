#!/bin/bash

# Compile all .java files in materials/src into the build/ directory.
# javac -d build mirrors the package hierarchy, so ir/*.java -> build/ir/, etc.
mkdir -p build

find materials/src -name "*.java" > sources.txt
javac -d build @sources.txt
EXIT_CODE=$?
rm sources.txt

if [ $EXIT_CODE -ne 0 ]; then
  echo "Compilation failed." >&2
  exit 1
fi

echo "Build successful."