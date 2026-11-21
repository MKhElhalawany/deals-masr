#!/usr/bin/env bash
# Compiles + runs the pure-Kotlin core tests WITHOUT the Android SDK (uses Gradle's bundled kotlinc).
set -euo pipefail
cd "$(dirname "$0")/.."
L=${GRADLE_LIB:-/opt/gradle/lib}
OUT=$(mktemp -d)
java -cp "$(ls $L/*.jar | tr '\n' ':')" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar" -jvm-target 17 -d "$OUT" \
  app/src/main/java/com/dealsmasr/app/core/*.kt app/src/test/java/com/dealsmasr/app/core/*.kt
java -cp "$OUT:$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar" org.junit.runner.JUnitCore com.dealsmasr.app.core.CoreLogicTest
