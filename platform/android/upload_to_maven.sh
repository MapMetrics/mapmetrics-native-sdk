#!/bin/bash

# Upload MapMetrics Android SDK to Maven Central
# Version 1.0.3

MAVEN_REPO_URL="https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
USERNAME="EGiLiBy0"
PASSWORD="ymdh85m+x49Li4tYvry0+kne7099RwGLMdYr/wJDzwmo"
GROUP_ID="org.mapmetrics.android-sdk"
ARTIFACT_ID="mapmetrics-native-sdk"
VERSION="1.0.3"

# Directory containing the artifacts
ARTIFACT_DIR="/Users/muhammad/.m2/repository/org/mapmetrics/android-sdk/mapmetrics-native-sdk/1.0.3"

cd "$ARTIFACT_DIR"

echo "Uploading MapMetrics Android SDK v$VERSION to Maven Central..."

# Upload main AAR
curl -v -u "$USERNAME:$PASSWORD" \
  --upload-file "${ARTIFACT_ID}-${VERSION}.aar" \
  "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.aar"

# Upload POM
curl -v -u "$USERNAME:$PASSWORD" \
  --upload-file "${ARTIFACT_ID}-${VERSION}.pom" \
  "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.pom"

# Upload sources JAR
curl -v -u "$USERNAME:$PASSWORD" \
  --upload-file "${ARTIFACT_ID}-${VERSION}-sources.jar" \
  "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}-sources.jar"

# Upload Gradle module metadata
curl -v -u "$USERNAME:$PASSWORD" \
  --upload-file "${ARTIFACT_ID}-${VERSION}.module" \
  "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.module"

# Upload all signature files
for file in *.asc; do
  echo "Uploading signature: $file"
  curl -v -u "$USERNAME:$PASSWORD" \
    --upload-file "$file" \
    "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/$file"
done

# Upload all checksum files
for file in *.md5 *.sha1; do
  echo "Uploading checksum: $file"
  curl -v -u "$USERNAME:$PASSWORD" \
    --upload-file "$file" \
    "${MAVEN_REPO_URL}org/mapmetrics/android-sdk/${ARTIFACT_ID}/${VERSION}/$file"
done

echo "Upload completed! Check Maven Central staging repository."