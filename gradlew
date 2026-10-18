#!/usr/bin/env sh
# Gradle wrapper stub - downloads Gradle 8.7 from services.gradle.org
# In restricted env, use local gradle install if available
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
else
  echo "Gradle wrapper jar missing - install Android SDK & run gradle wrapper task"
  exit 1
fi
