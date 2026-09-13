# Android client

Open this directory (`android/`) in Android Studio. The FastAPI backend remains
at the repository root (`app/`, `pyproject.toml`, `dev.ps1`); see the root README
for backend setup.

Android Studio can create your local SDK configuration in `local.properties`.
Machine-specific settings, build outputs and signing keys are excluded from Git.
The Gradle wrapper and version catalog are included.

The API base URL is configured in
`app/src/main/java/com/example/helloword/api/RetrofitClient.kt`.
Set it to the address of your running backend when using a different network.

From this directory, run `gradlew.bat testDebugUnitTest assembleDebug` on Windows
to run unit tests and build the debug APK with a configured Android SDK.
