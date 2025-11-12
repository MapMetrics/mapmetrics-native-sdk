#!/bin/bash

# Regenerate GPG signatures for MapMetrics Android SDK v1.0.3
# This script uses the GPG key configured in Gradle

echo "Regenerating GPG signatures for version 1.0.3..."

# Set the artifact directory
ARTIFACT_DIR="/Users/muhammad/.m2/repository/org/mapmetrics/android-sdk/mapmetrics-native-sdk/1.0.3"
cd "$ARTIFACT_DIR"

# GPG configuration from Gradle
KEY_ID="58197F4A"
KEY_PASSWORD="Hold-Sharp4-Purple-Final-Eventually"
KEY_RING_FILE="/Users/muhammad/StudioProjects/mapmetrics-native-sdk/platform/android/buildSrc/src/main/secretkeyringfile.gpg"

echo "Working in directory: $ARTIFACT_DIR"

# Check if gpg is available
if command -v gpg >/dev/null 2>&1; then
    echo "Using system GPG..."
    GPG_CMD="gpg"
elif command -v gpg2 >/dev/null 2>&1; then
    echo "Using GPG2..."
    GPG_CMD="gpg2"
else
    echo "ERROR: GPG not found. Please install GPG first:"
    echo "  brew install gnupg"
    exit 1
fi

# Import the key from the keyring file
echo "Importing GPG key from keyring file..."
$GPG_CMD --import "$KEY_RING_FILE"

# Files that need to be signed (updated content)
FILES_TO_SIGN="mapmetrics-native-sdk-1.0.3.pom mapmetrics-native-sdk-1.0.3.module"

# Remove old signatures for files with updated content
for file in $FILES_TO_SIGN; do
    echo "Removing old signature for $file..."
    rm -f "${file}.asc"
done

# Generate new signatures
for file in $FILES_TO_SIGN; do
    if [ -f "$file" ]; then
        echo "Signing $file..."
        echo "$KEY_PASSWORD" | $GPG_CMD --batch --yes --passphrase-fd 0 \
            --local-user "$KEY_ID" \
            --armor \
            --detach-sig \
            --output "${file}.asc" \
            "$file"
        
        if [ $? -eq 0 ]; then
            echo "✓ Successfully signed $file"
        else
            echo "✗ Failed to sign $file"
        fi
    else
        echo "✗ File not found: $file"
    fi
done

echo "Signature regeneration completed."
echo ""
echo "Files ready for upload:"
ls -la mapmetrics-native-sdk-1.0.3.*