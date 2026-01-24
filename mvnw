#!/bin/sh
# Maven Wrapper script
# Downloads Maven if not present and runs it

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

# Read properties
if [ -f "$MAVEN_WRAPPER_PROPERTIES" ]; then
    MAVEN_URL=$(grep 'distributionUrl' "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2)
fi

MAVEN_URL="${MAVEN_URL:-https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip}"

# Maven home
MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}"
MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/apache-maven-3.9.6"

# Download Maven if not present
if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven..."
    mkdir -p "$MAVEN_USER_HOME/wrapper/dists"
    TEMP_FILE=$(mktemp)
    curl -fsSL "$MAVEN_URL" -o "$TEMP_FILE"
    unzip -q "$TEMP_FILE" -d "$MAVEN_USER_HOME/wrapper/dists"
    rm "$TEMP_FILE"
fi

MAVEN_CMD="$MAVEN_HOME/bin/mvn"

# Run Maven
exec "$MAVEN_CMD" "$@"
