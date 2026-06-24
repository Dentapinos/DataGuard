#!/usr/bin/env bash
set -euo pipefail

MODE=${1:-all}   # all | unit | it

echo "Running tests in mode: $MODE"

case "$MODE" in
  unit)
    echo "Running UNIT tests only (mvn test)..."
    mvn -B test
    ;;

  it)
    echo "Running INTEGRATION tests only (mvn failsafe:integration-test)..."
    # Выполнить только фазу integration-test + verify для IT
    mvn -B -DskipTests=true failsafe:integration-test failsafe:verify
    ;;

  all)
    echo "Running UNIT + INTEGRATION tests (mvn verify)..."
    mvn -B verify
    ;;

  *)
    echo "Unknown mode: $MODE"
    echo "Usage: $0 [all|unit|it]"
    exit 1
    ;;
esac

echo "Tests finished successfully in mode: $MODE"
