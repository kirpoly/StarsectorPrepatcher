#!/usr/bin/env bash
set -euo pipefail
MOD_ROOT="$(cd "$(dirname "$0")" && pwd)"
GAME_ROOT="$(cd "$MOD_ROOT/../.." && pwd)"
CORE="$GAME_ROOT/starsector-core"
BUILD="$MOD_ROOT/.build"
rm -rf "$BUILD"
mkdir -p "$BUILD/agent-classes" "$BUILD/bootstrap-classes" "$MOD_ROOT/agent" "$MOD_ROOT/jars"
CP_AGENT="$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar"
CP_BOOT="$CORE/starfarer.api.jar:$CORE/starfarer_obf.jar:$CORE/log4j-1.2.9.jar"
find "$MOD_ROOT/source/agent" -name '*.java' -print0 | xargs -0 javac -source 17 -target 17 \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED \
  -cp "$CP_AGENT" -d "$BUILD/agent-classes"
find "$MOD_ROOT/source/bootstrap" -name '*.java' -print0 | xargs -0 javac -source 17 -target 17 \
  -cp "$CP_BOOT" -d "$BUILD/bootstrap-classes"
printf '%s\n' \
  'Manifest-Version: 1.0' \
  'Premain-Class: com.starsector.mapoptimizer.agent.MapOptimizerAgent' \
  'Can-Redefine-Classes: false' \
  'Can-Retransform-Classes: false' '' > "$BUILD/agent.mf"
printf '%s\n' \
  'Manifest-Version: 1.0' \
  'Implementation-Title: Starsector Map Optimizer Bootstrap' \
  'Implementation-Version: 0.4.0-exp4' '' > "$BUILD/bootstrap.mf"
jar cfm "$MOD_ROOT/agent/StarsectorMapOptimizerAgent.jar" "$BUILD/agent.mf" -C "$BUILD/agent-classes" .
jar cfm "$MOD_ROOT/jars/StarsectorMapOptimizerBootstrap.jar" "$BUILD/bootstrap.mf" -C "$BUILD/bootstrap-classes" .
echo 'Build completed.'
