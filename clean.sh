#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Removing .class files..."
find "$SCRIPT_DIR" -name "*.class" -delete

echo "Running mvn clean..."
(cd "$SCRIPT_DIR/materials/mips-interpreter" && mvn clean -q)

echo "Done."
