#!/usr/bin/env sh
GRADLE_VERSION=8.4
GRADLE_HOME=$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin
if [ ! -f "$GRADLE_HOME/gradle-${GRADLE_VERSION}/bin/gradle" ]; then
  mkdir -p $GRADLE_HOME
  wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -P $GRADLE_HOME
  unzip -q $GRADLE_HOME/gradle-${GRADLE_VERSION}-bin.zip -d $GRADLE_HOME
fi
exec $GRADLE_HOME/gradle-${GRADLE_VERSION}/bin/gradle "$@"
