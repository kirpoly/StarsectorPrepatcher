#!/usr/bin/env bash
set -euo pipefail

MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
GAME_ROOT="$(cd "$MOD_ROOT/../.." && pwd)"
BUILD="$MOD_ROOT/.build"
AGENT_CLASSES="$BUILD/agent-classes"
TEST_CLASSES="$BUILD/test-classes"

bash "$MOD_ROOT/build-agent.sh"
mkdir -p "$TEST_CLASSES"
find "$MOD_ROOT/source/test" -name '*.java' -print0 | xargs -0 javac -source 17 -target 17 \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED \
  -cp "$AGENT_CLASSES" -d "$TEST_CLASSES"

if (( $# == 0 )); then
  CORE_JARS=("$GAME_ROOT/starsector-core/starfarer_obf.jar")
else
  CORE_JARS=("$@")
fi

CLASS_PATH="$AGENT_CLASSES:$TEST_CLASSES"
java \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED \
  -cp "$CLASS_PATH" com.starsector.mapoptimizer.agent.StructuralCompatibilityTest \
  "$MOD_ROOT/optimizer.properties" "${CORE_JARS[@]}"

LIFECYCLE_CLASS_PATH="$TEST_CLASSES:$MOD_ROOT/agent/StarsectorMapOptimizerAgent.jar:$GAME_ROOT/starsector-core/starfarer.api.jar:$GAME_ROOT/starsector-core/starfarer_obf.jar:$GAME_ROOT/starsector-core/lwjgl.jar:$GAME_ROOT/starsector-core/lwjgl_util.jar"
java -cp "$LIFECYCLE_CLASS_PATH" com.starsector.mapoptimizer.runtime.LifecycleGcRegressionTest
java -cp "$LIFECYCLE_CLASS_PATH" com.starsector.mapoptimizer.runtime.Exp6RuntimeRegressionTest
