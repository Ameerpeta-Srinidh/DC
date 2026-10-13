#!/bin/bash
# Compiles every .java file into ./out
set -e
cd "$(dirname "$0")"
mkdir -p out
find . -path ./out -prune -o -name "*.java" -print > sources.txt
javac -cp "lib/*" -d out @sources.txt
rm sources.txt
echo "Build OK -> ./out"
