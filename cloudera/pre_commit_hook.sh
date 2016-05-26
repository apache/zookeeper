#!/bin/bash -e

echo "Running pre-commit unit tests..."

ant clean test-core-java
