# Building the Android app

## Requirements

- JDK 21 (Gradle toolchain is pinned through the wrapper; Java source/bytecode target 17).
- Android SDK Platform 37 and Build Tools 36.x or newer (the current Compose BOM requires compileSdk 37).
- Minimum supported Android is API 24 (ONNX Runtime Android 1.23.2 requirement).
- Android Studio is optional for command-line builds. Emulator/device tests require an installed AVD or attached Android device.

The repository-local `local.properties` is machine-specific and ignored by Git; set `sdk.dir` to your Android SDK path if Gradle cannot locate it. The app compiles/targets API 37. App data backup is disabled to keep seed and roll history device-local.

## Common tasks

From PowerShell at the project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

`connectedDebugAndroidTest` needs an emulator/device. The stage OCR instrumentation test runs the supplied screenshot through ONNX Runtime's CPU provider. Do not invoke connected tests on the user's physical phone unless they explicitly ask; Genymotion is the preferred target. ONNX Runtime uses CPU execution and does not require Google Play services or NNAPI.

## Current scope caveat

The app includes Room-backed seed profiles and roll logging, the tested A/B seed engine, and a local stage catalog parser. The stage helper requests MediaProjection approval once when the helper session starts, keeps the screen-share service active with its virtual-display surface detached while idle, and attaches the ImageReader only for paw taps. English recognition uses PP-OCRv5; Japanese uses the Manga OCR encoder/decoder via ONNX Runtime CPU. The overlay shows recognized text, a centered-card candidate, and alternatives; the user confirms the stage. Drag the floating paw to the bottom stop target to end the helper and screen-sharing session. The latest crash popup did not include a captured stack trace, so its precise cause remains unconfirmed; the capture service now surfaces setup/inference failures in the overlay. Missing names/icons fall back to IDs.

The bundled ONNX weights add about 72.6 MB of model assets, copied from `E:\Fukidashi\models\ocr` with SHA-256 recorded in `app/src/main/assets/ocr/source-manifest.json`.

## Build warning status

Most recent local unit tests, lint and debug APK assemblies passed with AGP 9.5.0 / Gradle 9.8.2. The Gradle wrapper still reports its Gradle 10 compatibility deprecation notice; use `--warning-mode all` to locate the third-party source.
