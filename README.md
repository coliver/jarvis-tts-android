# Jarvis TTS for Android

A fully on-device voice assistant for Android.

Tap **Ask Jarvis**, speak, and get a spoken response:

1. 🎙️ Records your voice
2. ✍️ Converts speech to text
3. 🧠 Generates a reply
4. 🔊 Reads the reply aloud

No server, PC, or permanent network connection is required.

Models download over HTTP the first time you use them or switch models.

> Tested end-to-end on a Pixel 8 Pro.

For build details, architecture notes, and the current backlog, see [`AGENTS.md`](AGENTS.md).

## How it works

```text
Tap “Ask Jarvis”
      ↓
Voice activity detection
      ↓
Whisper.cpp — speech to text
      ↓
Llama.cpp — generates a reply
      ↓
Pocket-TTS / omatts — text to speech
      ↓
AudioTrack — streams the audio
```

Recording stops automatically when you stop speaking. You do not need to hold down a button for a fixed amount of time.

All three engines warm up in parallel when the app starts. Loading and download progress is shown for each engine.

## Models

### Speech-to-text

- **Engine:** `whisper.cpp`
- **Default model:** `ggml-tiny.en-q5_1.bin`
- **Size:** Approximately 31 MB
- **Download:** Hugging Face, on first use

### Language model

- **Engine:** `llama.cpp`
- **Default model:** `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
- **Size:** Approximately 770 MB
- **Download:** On first use

You can use a different llama.cpp-compatible model:

```bash
adb push model.gguf \
  /sdcard/Android/data/com.jarvistts/files/llm/
```

Then select it from the model picker in the top bar while the app is idle.

Chat formatting is detected automatically from the loaded model. No code changes are needed.

### Text-to-speech

- **Engine:** Pocket-TTS through [`parnoldx/omatts`](https://github.com/parnoldx/omatts)
- **Models:** Bundled in `app/src/main/assets/`
- **Download:** Not currently available for the custom-exported models

STT and LLM models are downloaded instead of bundled so the APK stays around **159 MB**, rather than approaching 1 GB.

## Build requirements

You do not need Android Studio.

The project keeps its toolchain inside the repository’s gitignored `.toolchain/` directory:

- JDK 17 — Temurin
- Android command-line tools
- Android platform 34
- Android Build Tools 34.0.0
- Android NDK 27.2.12479018
- CMake 3.31.6
- Ninja

The project requires:

- CMake 3.28 or newer
- Android NDK 27.x
- WSL/Linux command line

`local.properties` should point to:

```text
sdk.dir=<repo>/.toolchain/android-sdk
```

## Build, test, and lint

Set the environment variables:

```bash
export JAVA_HOME=<repo>/.toolchain/jdk-17.0.20.1+1
export ANDROID_HOME=<repo>/.toolchain/android-sdk
```

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run lint checks:

```bash
./gradlew ktlintCheck
```

Automatically fix formatting:

```bash
./gradlew ktlintFormat
```

Generate a test coverage report:

```bash
./gradlew jacocoTestReport
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install on a phone from WSL2

WSL2 usually cannot access a phone over USB directly.

Choose one of these options.

### Option 1: Wireless debugging

1. Enable **Wireless debugging** in Android Developer Options.
2. Pair the phone:

   ```bash
   adb pair <phone-ip>:<pairing-port>
   ```

3. Connect to the phone:

   ```bash
   adb connect <phone-ip>:<port>
   ```

4. Install the APK:

   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

Wireless debugging can be unreliable during large file transfers.

### Option 2: Windows ADB over USB

If Windows already detects the phone with ADB:

1. Build the APK in WSL.
2. Copy it to a Windows path.
3. Install it using Windows ADB through PowerShell.

Example:

```bash
cp app/build/outputs/apk/debug/app-debug.apk \
  /mnt/c/Users/<you>/AppData/Local/Temp/
```

Then run this in PowerShell:

```powershell
adb install $env:TEMP\app-debug.apk
```

`adb install` does not work directly with UNC paths such as:

```text
\\wsl.localhost\...
```

## Sharing the APK

The debug APK can be sideloaded onto another device.

The receiving device needs:

- Android 8.0 or newer
- An ARM64 processor
- Permission to install unknown apps

The APK is approximately **159 MB**.

Good transfer options include:

- A Drive link
- USB
- Local file transfer

Avoid sending it as a chat attachment.

## Project layout

```text
app/src/main/
├── java/com/jarvistts/
│   ├── MainActivity.kt
│   │   Activity lifecycle, coroutines, AudioRecord, and AudioTrack
│   ├── JarvisScreen.kt
│   │   Jetpack Compose UI
│   ├── NativeSTT.kt
│   │   JNI declarations for whisper.cpp
│   ├── NativeLLM.kt
│   │   JNI declarations for llama.cpp
│   ├── NativeBridge.kt
│   │   JNI declarations for the TTS engine
│   ├── VoicePipeline.kt
│   │   Transcript cleanup, VAD, and testable voice logic
│   ├── ModelManager.kt
│   │   Model discovery, download URLs, and model selection
│   └── ModelDownloader.kt
│       Model downloads and progress reporting
│
├── cpp/
│   ├── CMakeLists.txt
│   ├── omatts.cpp
│   ├── jni_bridge.cpp
│   ├── stt_jni_bridge.cpp
│   └── llm_jni_bridge.cpp
│
├── assets/
│   ├── models/
│   │   TTS ONNX models and tokenizer
│   └── voices/
│       └── jarvis-03.wav
│
└── src/test/java/com/jarvistts/
    └── VoicePipelineTest.kt
```

STT and LLM models are not included in `assets/`. They download the first time they are needed.

The TTS model is currently bundled with the app.

## Native libraries

The native build creates three shared libraries:

```text
jarvis_tts
jarvis_stt
jarvis_llm
```

They contain:

- `jarvis_tts` — omatts and ONNX Runtime
- `jarvis_stt` — whisper.cpp
- `jarvis_llm` — llama.cpp

The build intentionally forces Release mode:

```cmake
CMAKE_BUILD_TYPE=Release
```

This is important. Without it, an Android Gradle Plugin issue can silently produce builds that are **30–80× slower**.

See `AGENTS.md` for the full explanation.

## Exporting the TTS models

The bundled ONNX models were generated using omatts’s `export_onnx.py`.

Use this specific Pocket-TTS commit:

```text
kyutai-labs/pocket-tts
commit: 7db278e
```

Later Pocket-TTS versions removed or renamed modules and symbols required by the export script.

Install the pinned checkout without installing its dependencies:

```bash
pip install --no-deps <path-to-pocket-tts-checkout>
```

The export environment needs:

- `pocket-tts`
- `torch`
- `onnx`
- `onnxruntime`

Then run:

```bash
python export_onnx.py
```

The script:

- Exports five models
- Quantizes three models to INT8
- Stores large weights in `.onnx.data` sidecar files
- Keeps one default variant per model

Copy the generated directory into:

```text
app/src/main/assets/models/
```

## Licenses

- Project code and `omatts.cpp`: MIT
- `whisper.cpp`: MIT
- `llama.cpp`: MIT
- TTS model weights: CC-BY-4.0
- Full attribution: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- TTS weight license: [`app/src/main/assets/models/LICENSE-WEIGHTS.txt`](app/src/main/assets/models/LICENSE-WEIGHTS.txt)
