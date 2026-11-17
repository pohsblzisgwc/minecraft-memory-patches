#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bash patch-ledger-queue-guard/build.sh
MC_DIR="${MC_DIR:?Set MC_DIR to your local Fabric server directory}"
TEST_BUILD="$(mktemp -d /tmp/ledger-recovery-test-classes.XXXXXX)"
CP="patch-ledger-queue-guard/build/classes:$(find patch-ledger-queue-guard/build/deps "$MC_DIR/libraries" -name '*.jar' -printf '%p:')"
javac -proc:none --release 25 -cp "$CP" -d "$TEST_BUILD" tests/database-recovery/RecoveryTest.java
java -cp "$TEST_BUILD:$CP" RecoveryTest
