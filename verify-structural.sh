#!/usr/bin/env bash
set -euo pipefail

MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
GAME_ROOT="$(cd "$MOD_ROOT/../.." && pwd)"
CORE="$GAME_ROOT/starsector-core"
BUILD="$MOD_ROOT/.build"
AGENT_CLASSES="$BUILD/agent-classes"
TEST_CLASSES="$BUILD/test-classes"
REPORT_DIR="$BUILD/reports"
EXPORTS=(
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED
)

bash "$MOD_ROOT/build-agent.sh"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES" "$REPORT_DIR"

TEST_CP="$AGENT_CLASSES:$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar"
find "$MOD_ROOT/source/test" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -source 17 -target 17 \
  "${EXPORTS[@]}" -cp "$TEST_CP" -d "$TEST_CLASSES"

java -cp "$TEST_CLASSES" \
  com.starsector.prepatcher.docs.DocumentationConsistencyTest "$MOD_ROOT" \
  2>&1 | tee "$REPORT_DIR/documentation-consistency.txt"

if (( $# == 0 )); then
  CORE_JARS=(
    "$CORE/starfarer_obf.jar"
    "$CORE/fs.common_obf.jar"
    "$CORE/fs.sound_obf.jar"
    "$CORE/starfarer.api.jar"
  )
else
  CORE_JARS=("$@")
fi

CLASS_PATH="$AGENT_CLASSES:$TEST_CLASSES"
VERIFICATION_CONFIG="$BUILD/structural-all-enabled.properties"
{
  printf '%s\n' '# Generated for structural coverage only; never shipped or used by startup smoke.'
  sed \
    -e 's/^patch\.loadingTextReader[[:space:]]*=.*/patch.loadingTextReader=true/' \
    -e 's/^patch\.startupLogAggregation[[:space:]]*=.*/patch.startupLogAggregation=true/' \
    "$MOD_ROOT/profiles/aggressive.properties"
} > "$VERIFICATION_CONFIG"
grep -qx 'patch.loadingTextReader=true' "$VERIFICATION_CONFIG"
grep -qx 'patch.startupLogAggregation=true' "$VERIFICATION_CONFIG"
java "${EXPORTS[@]}" -cp "$CLASS_PATH" \
  com.starsector.prepatcher.agent.StructuralCompatibilityTest \
  "$VERIFICATION_CONFIG" "${CORE_JARS[@]}" \
  2>&1 | tee "$REPORT_DIR/structural-verification.txt"

RUNTIME_CP="$TEST_CLASSES:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar:$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/fs.common_obf.jar:$CORE/fs.sound_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar"
{
  echo '== LifecycleGcRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.LifecycleGcRegressionTest
  echo '== Exp6RuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.Exp6RuntimeRegressionTest
  echo '== Exp8RuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.Exp8RuntimeRegressionTest
  echo '== LoadingSaveRuntimeRegressionTest =='
  java -cp "$RUNTIME_CP" com.starsector.prepatcher.runtime.LoadingSaveRuntimeRegressionTest
} 2>&1 | tee "$REPORT_DIR/runtime-regression.txt"

java "${EXPORTS[@]}" -cp "$TEST_CLASSES:$TEST_CP" \
  com.starsector.prepatcher.agent.HyperspaceCompatibilityTest \
  "$VERIFICATION_CONFIG" "$CORE/starfarer_obf.jar" "$CORE/starfarer.api.jar" \
  "$REPORT_DIR/hyperspace-verification.txt"

java \
  "-javaagent:$MOD_ROOT/agent/StarsectorPrepatcherAgent.jar" \
  -version 2>&1 | tee "$REPORT_DIR/startup-smoke.txt"

echo 'Documentation/structural/runtime/hyperspace/startup verification completed.'
