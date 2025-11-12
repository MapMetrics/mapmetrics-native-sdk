# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Core Architecture

MapLibre Native is a C++ cross-platform mapping library with platform-specific bindings. This is a monorepo containing:

- **Core C++ library**: `include/` (public headers) and `src/` (implementation)
- **Platform SDKs**: `platform/android`, `platform/ios`, `platform/macos`, `platform/node`, `platform/glfw`
- **Build system**: Uses Make as the coordinator, with platform-specific tools (Gradle for Android, Xcode for iOS, CMake)
- **Testing**: `test/` (C++ unit tests), `render-test/` (visual regression tests), `benchmark/` (performance tests)

## Key Architectural Components

### Threading Model
- **Main thread**: Handles direct Map API requests, owns the active Style, renders the map
- **Worker threads**: 4 per Style object, handle parsing vector tiles, text layout, and data buffers
- **FileSource thread**: Network requests and SQLite I/O for offline maps and caching

### Immutability Pattern
- Public classes (`Layer`, `Source`) are mutable with runtime styling API
- Private implementations (`Layer::Impl`, `Source::Impl`) are immutable and shared between threads
- Style diffing is used to efficiently communicate changes between main and render threads

### Build System Flow
1. Make coordinates all build tools
2. Mason package manager provides C++ dependencies
3. Platform-specific tools complete the build (Gradle, Xcode, etc.)

## Common Development Commands

### Android Development
```bash
# Build Android SDK and test app (arm-v7)
make android

# Build for specific architecture
make android-arm-v8
make android-x86-64

# Run tests
make run-android-unit-test                    # JVM unit tests
make run-android-ui-test                      # Instrumentation tests on device
make run-android-core-test-arm-v7             # Native C++ tests

# Code quality
make android-check                            # Run all checks (lint + checkstyle)
make android-lint-sdk                        # Lint Android SDK
make android-checkstyle                       # Java/Kotlin checkstyle
make android-ktlint                          # Kotlin lint

# Build release package
make apackage BUILDTYPE=Release RENDERER=drawable
```

### Android Gradle Commands (via platform/android/)
```bash
cd platform/android
./gradlew :MapLibreAndroid:assembleDebug                    # Build SDK
./gradlew :MapLibreAndroidTestApp:assembleDebug            # Build test app
./gradlew :MapLibreAndroid:testDrawableDebugUnitTest        # Unit tests
./gradlew :MapLibreAndroidTestApp:connectedAndroidTest      # Instrumentation tests
```

### Core C++ Development
```bash
# Generate style code
make android-style-code
node scripts/generate-style-code.js

# Clean build artifacts
make clean
```

### Node.js Development
```bash
cd platform/node
npm test                        # All tests
npm run test-render            # Render tests
npm run test-expressions       # Expression tests
```

## Project Structure Notes

- **Vendor dependencies**: `vendor/` contains git submodules and third-party code
- **Test data**: `metrics/` contains render test expectations and test data
- **Shaders**: `shaders/` contains GLSL shaders for OpenGL/Vulkan rendering
- **Documentation**: `docs/` contains platform-specific docs, `ARCHITECTURE.md` for core concepts

## Platform-Specific Notes

### Android
- Main SDK code: `platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/`
- JNI bindings: `platform/android/MapLibreAndroid/src/cpp/`
- Test app: `platform/android/MapLibreAndroidTestApp/`
- Uses Gradle build system with CMake for native code

### iOS
- Uses Bazel to generate Xcode projects
- Run: `bazel run //platform/ios:xcodeproj --@rules_xcodeproj//xcodeproj:extra_common_flags="--//:renderer=metal"`

## Style Generation
The project auto-generates code from the MapLibre Style Specification. Run style code generation after modifying style specs:
```bash
make android-style-code
```

## Testing Strategy
- **Unit tests**: Fast C++ tests in `test/`, Java/Kotlin tests in platform dirs
- **Render tests**: Visual regression tests comparing rendered output to expected images
- **Benchmark tests**: Performance measurement tests
- **Integration tests**: Platform-specific UI tests for real device scenarios

## Important Configuration
- **Build types**: Debug, Release, RelWithDebInfo, Sanitize
- **Renderers**: `drawable` (OpenGL), `vulkan`
- **Android ABIs**: arm-v7, arm-v8, x86, x86-64
- Set via environment variables: `BUILDTYPE=Release RENDERER=vulkan`

## Development Workflow
1. Use platform-specific development environments (Android Studio for Android)
2. Run appropriate test suite after changes
3. Use `make android-check` for code quality validation
4. Generate style code when modifying style specifications
5. Cross-platform changes require testing on multiple platforms