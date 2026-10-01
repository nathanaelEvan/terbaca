# Terbaca

Terbaca is a high-performance, read-only PDF reader for Android tablets, specifically optimized for high-density, 144 Hz displays like the Xiaomi Pad 7. Designed to feel as instantaneous and crisp as macOS Preview and Apple Books, it offers razor-sharp rendering up to 800% zoom, fluid 144 Hz gestures, a dedicated background render process with zero UI jank, and tailored reading modes for academic papers and pitch decks—strictly offline with zero ads, tracking, or network permissions.

## Building from Source

### Prerequisites
- JDK 21 or newer (e.g. Eclipse Temurin 21)
- Android SDK with platform `android-36` and Build-Tools `36.0.0`
- Target ABI: `arm64-v8a`

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests across all modules
./gradlew test

# Run Android Lint
./gradlew lintDebug

# Run code style and static analysis
./gradlew detekt
```
