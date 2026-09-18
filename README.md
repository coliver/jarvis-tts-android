# Jarvis TTS (Android)

An on-device Android voice assistant. Tap "Ask Jarvis," speak, and it
transcribes, thinks, and talks back, entirely on-device: no server, no PC,
no persistent network dependency (models fetch over HTTP once, on first
launch or model switch).

Status: proven end-to-end on a Pixel 8 Pro. See `AGENTS.md` for build
instructions, architecture detail, and the current backlog if you're
working on this codebase (human or agent).

## How it works

```
mic tap -> VAD-gated recording -> whisper.cpp (STT)
        -> llama.cpp (LLM, chat-template auto-detected from the .gguf)
        -> Pocket-TTS/omatts via ONNX Runtime (TTS)
        -> AudioTrack streaming playback (pausable)
```

Recording auto-stops on silence (no fixed-duration button hold). All three
engines warm up in parallel on app launch, with live loading/download
percentages shown per engine. The LLM is swappable at runtime, see "Models"
below.

## Project layout

```
app/src/main/
  java/com/jarvistts/
    MainActivity.kt      Activity lifecycle, coroutine orchestration, AudioRecord/AudioTrack
    JarvisScreen.kt       all Compose UI
    NativeSTT.kt          JNI declarations for whisper.cpp
    NativeLLM.kt           JNI declarations for llama.cpp
    NativeBridge.kt        JNI declarations for the TTS engine
    VoicePipeline.kt      pure logic pulled out for unit testing: transcript cleanup, VAD
    ModelManager.kt        model discovery, download URLs, user-selection persistence
    ModelDownloader.kt     streams a model URL to disk with progress
  cpp/
    CMakeLists.txt        Android-adapted build for all three native libs (see AGENTS.md)
    omatts.cpp             copied verbatim from parnoldx/omatts
    jni_bridge.cpp          thin wrappers around omatts's ptt_* C API
    stt_jni_bridge.cpp      thin wrappers around whisper.cpp
    llm_jni_bridge.cpp      thin wrappers around llama.cpp
  assets/
    models/                 TTS: exported .onnx + .onnx.data models, tokenizer
    voices/jarvis-03.wav    TTS: cloned voice sample
  src/test/java/com/jarvistts/
    VoicePipelineTest.kt    fast local JUnit tests (no emulator needed)
```

STT and LLM model files are **not** bundled as assets, they're downloaded
on first use (see "Models"). TTS still is, for now.

## Models

- **Speech-to-text**: whisper.cpp, `ggml-tiny.en-q5_1.bin` (~31MB),
  downloaded from Hugging Face on first use.
- **Language model**: llama.cpp, `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
  (~770MB) by default, also downloaded on first use. Swap it: drop any
  `.gguf` llama.cpp supports into the app's external files directory
  (`adb push model.gguf /sdcard/Android/data/com.jarvistts/files/llm/`) and
  pick it from the model picker in the top bar while idle. Chat prompt
  formatting auto-detects from whichever model is loaded (via llama.cpp's
  `llama_chat_apply_template`), no code changes needed per model.
- **Text-to-speech**: Pocket-TTS via `parnoldx/omatts`, bundled in
  `assets/` (custom-exported, no public URL to download from yet, see
  "Model export" below).

Downloading instead of bundling keeps the installable APK around 159MB
instead of ~1GB, small enough to hand to someone else directly.

## Native build

`app/src/main/cpp/CMakeLists.txt` builds three shared libraries:
`jarvis_tts` (omatts/ONNX Runtime), `jarvis_stt` (whisper.cpp, via
`FetchContent`), `jarvis_llm` (llama.cpp, via an isolated
`ExternalProject_Add` — it and whisper.cpp both vendor their own `ggml`
with colliding target names, so they can't share one CMake configure).

Forces `CMAKE_BUILD_TYPE=Release` unconditionally near the top of the file.
**This is load-bearing, not cosmetic** — see AGENTS.md for the AGP bug this
works around (it once caused a 30-80x slowdown across all whisper.cpp/omatts
code by silently building at `-O0`).

Requires CMake 3.28+ and NDK 27.x (see Toolchain below).

## Toolchain (self-contained, WSL/Linux, command-line only)

No Android Studio. Everything lives in `.toolchain/` (gitignored) inside
this repo, not installed system-wide:

- JDK 17 (Temurin)
- Android `cmdline-tools`, then via its `sdkmanager`:
  `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`,
  `ndk;27.2.12479018`, `cmake;3.31.6` (bundles Ninja)
- `local.properties` sets `sdk.dir=<repo>/.toolchain/android-sdk`

Build, test, lint:

```bash
export JAVA_HOME=<repo>/.toolchain/jdk-17.0.20.1+1
export ANDROID_HOME=<repo>/.toolchain/android-sdk
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew ktlintCheck      # ./gradlew ktlintFormat to auto-fix
./gradlew jacocoTestReport
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a device from WSL2

WSL2 has no USB passthrough to the phone by default. Two options:

- **Wireless debugging**: enable it in Developer Options, then
  `adb connect <phone-ip>:<port>` (pair first with `adb pair` if the phone
  shows a pairing code). Can be flaky mid-transfer on some networks.
- **USB via Windows adb**: if Windows already has `adb` (e.g. via
  Chocolatey) and sees the phone over USB, drive it from WSL through
  `powershell.exe -Command "adb install ..."`. Note: `adb install` fails on
  UNC paths (`\\wsl.localhost\...`), so copy the APK to a local Windows path
  (e.g. `$env:TEMP`) first.

## Sharing the APK

The debug APK is a normal sideloadable Android package. To hand it to
someone else: they need Android 8.0+ on an arm64 device, and to allow
"install unknown apps" for whatever they use to open the file (it's not
from the Play Store). Send it as a file (Drive link, USB, etc.), not a chat
attachment, 159MB is too big for most messaging apps.

## Model export (TTS only)

The ONNX models under `app/src/main/assets/models/` were produced by
omatts's `export_onnx.py`, run against a Python venv with `pocket-tts`,
`torch`, `onnx`, and `onnxruntime` installed.

Important: `export_onnx.py` imports a `pocket_tts.conditioners` module and a
few symbols (e.g. `DEFAULT_LSD_DECODE_STEPS`) that later Pocket-TTS releases
removed or renamed. Use an older pinned commit, verified working:
`kyutai-labs/pocket-tts` commit `7db278e`. Install it with:

```bash
pip install --no-deps <path-to-that-checkout>
```

then run `python export_onnx.py`. It exports 5 models, quantizes 3 of them
to INT8, externalizes weights into `.onnx.data` sidecars, and trims down to
one default variant per model (fp32 or int8, whichever the script picks).
Copy the resulting `models/` directory into `app/src/main/assets/models/`.

## Licenses

- Code (`omatts.cpp`, `parnoldx/omatts`): MIT. See `LICENSE`.
- TTS model weights: derived from Kyutai Labs' Pocket-TTS, CC-BY-4.0. See
  `app/src/main/assets/models/LICENSE-WEIGHTS.txt`.
- whisper.cpp, llama.cpp: MIT.
- Full attribution: `THIRD_PARTY_NOTICES.md`.
