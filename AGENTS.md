# Agent instructions

This file is for AI agents (Claude Code or otherwise) picking up work on
this repo. It's operational instructions, not a feature pitch, see
`README.md` for that. Read this before touching build files, native code,
or `MainActivity.kt`/`JarvisScreen.kt`.

## What this project is

An on-device Android voice assistant. Tap a button, speak, Jarvis answers
out loud. Everything runs locally on the phone, no server, no PC, no
network dependency except to download models on first launch.

Pipeline: mic -> VAD auto-stop recording -> whisper.cpp (STT) -> llama.cpp
(LLM) -> Pocket-TTS/omatts via ONNX Runtime (TTS) -> `AudioTrack` playback.

Proven working end-to-end on a Pixel 8 Pro as of 2026-09-17.

The repo has git history and a GitHub remote (`origin`) as of 2026-09-19.
Only commit or push when the user explicitly asks, don't take that
initiative unprompted. Read a file immediately before editing it, don't
assume your in-context copy is current -- other sessions may be working in
this same tree (see `ListAgents`).

Always update `CHANGELOG.md` alongside any user-visible change (feature, fix,
behavior change), under the current date, in the same working tree change so
it lands in the same commit.

## Build, test, lint

No Android Studio. Self-contained toolchain in `.toolchain/` (gitignored):

```bash
export JAVA_HOME=<repo>/.toolchain/jdk-17.0.20.1+1
export ANDROID_HOME=<repo>/.toolchain/android-sdk   # for tools other than gradlew
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # fast local JUnit tests (no emulator)
./gradlew ktlintCheck            # lint, must be 0 violations before considering work done
./gradlew ktlintFormat           # auto-fixes almost all ktlint violations (mechanical/whitespace)
./gradlew jacocoTestReport       # coverage report -> app/build/reports/jacoco/
```

**Run `ktlintCheck` and `testDebugUnitTest` after every change**, not just
at the end of a task. Both are fast (single-digit seconds once Gradle's
warm). There is no CI here catching this later; you are the CI.

`.editorconfig` at the repo root disables ktlint's function-naming rule for
`@Composable`-annotated functions (PascalCase is correct Compose style, not
a violation) — don't "fix" that by renaming Composables.

### Installing on the device

WSL2 has no USB passthrough to the phone. This session's working pattern:
Windows already has `adb.exe` (e.g. via Chocolatey) with direct USB access
to the phone. Drive it from WSL through `powershell.exe -Command "adb ..."`.
`adb install` fails on UNC paths (`\\wsl.localhost\...`), so copy the APK to
a local Windows path first, e.g.:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/<user>/AppData/Local/Temp/jarvis-apk/app-debug.apk
powershell.exe -Command "adb install -r 'C:\Users\<user>\AppData\Local\Temp\jarvis-apk\app-debug.apk'"
```

Wireless debugging (`adb connect <ip>:<port>`) is a fallback if USB isn't
available; it's less reliable (drops mid-transfer on this network).

Use `adb exec-out screencap -p > file.png` (via the same `powershell.exe`
bridge) to visually verify UI state instead of asking the user to describe
their screen.

**Gotcha**: after deleting large files from `app/src/main/assets/`, a plain
`assembleDebug` can still produce a huge APK because Gradle's incremental
packaging leaves stale bytes physically in the zip even though the central
directory no longer lists them. Run `./gradlew clean assembleDebug` and
recheck the size before trusting it.

## Critical native-build gotcha (read before touching CMakeLists.txt)

Android Gradle Plugin silently passes `-DCMAKE_BUILD_TYPE=Debug` for a
Gradle debug variant. This **defeats** a plain
`if(NOT CMAKE_BUILD_TYPE) set(... Release) endif()` guard, because the
variable is already set. The result is zero optimization (`-O0`) on all
native code compiled by the top-level CMake invocation, math-heavy code
like ggml's matmul kernels is catastrophically slow at `-O0` (whisper.cpp
transcription was 30-84s instead of ~1s before this was found and fixed).

The fix already in place: `set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)`
near the top of `app/src/main/cpp/CMakeLists.txt`, unconditional. **Do not
remove or make this conditional.** If native code ever seems mysteriously
slow again, check `app/.cxx/Debug/<hash>/arm64-v8a/compile_commands.json`
for actual `-O` flags before assuming it's an algorithmic problem.

`llama.cpp` builds via a separate `ExternalProject_Add` (isolated from the
top-level CMake configure, needed because llama.cpp and whisper.cpp both
vendor their own `ggml` with colliding target names). That isolated build
needs its **own** explicit `-DCMAKE_BUILD_TYPE=Release` in
`LLAMA_CMAKE_ARGS` — it does not inherit the parent's forced build type.

## Model strategy

- **STT** (`ggml-base.en-q5_1.bin`, ~57MB) and **LLM**
  (`Llama-3.2-3B-Instruct-Q4_K_M.gguf`, ~2GB) are downloaded on first use
  from public HuggingFace URLs (see `ModelManager.STT_URL` /
  `DEFAULT_LLM_URL`), not bundled in the APK. `ModelDownloader.kt` streams
  to a `.part` file, hashing as it writes, and only renames to the final
  name if the SHA-256 matches `ModelManager.STT_SHA256`/`DEFAULT_LLM_SHA256`
  -- so neither a killed download nor a completed-but-corrupt one (bit flip,
  a truncating proxy that still returns 200) can look like a valid model
  file. Those hash constants came from the `x-linked-etag` response header
  on a HEAD request to the model URL (HuggingFace's Git-LFS content hash),
  not a local computation -- re-derive them the same way if the URLs are
  ever repointed at different files.
- **TTS** (Pocket-TTS/omatts ONNX set + voice sample, ~190MB) is still
  bundled in `app/src/main/assets/`. Those files are custom-exported via a
  local Python pipeline (`export_onnx.py`, see README), not available at
  any public URL, so there's nothing to point a downloader at yet. Making
  TTS downloadable too would need the user to upload the exported files
  somewhere (GitHub Release, HuggingFace repo, etc.) first, then wiring a
  third `ModelDownloader.download()` call analogous to STT/LLM.
- The LLM is user-swappable at runtime: drop a `.gguf` into
  `ModelManager.externalModelsDir()` (app-specific external storage, no
  permissions needed, reachable via `adb push`) and pick it from the model
  settings sheet (sliders button in the top bar) while idle. Chat prompt formatting is **not**
  hardcoded per model, `NativeLLM.nativeFormatPrompt()` calls llama.cpp's
  `llama_chat_apply_template()`, which reads the template embedded in
  whatever `.gguf` is loaded. Don't reintroduce a hardcoded template string,
  that was deliberately removed.
- As of 2026-09-19, `nativeFormatPrompt()` also takes the recent conversation
  turns (`VoicePipeline.buildHistory()` picks which ones fit the remaining
  context budget), not just the current utterance, so follow-ups within a
  session have context. The budget is an estimate (~4 chars/token), not an
  exact tokenization -- see the comment on `LLM_HISTORY_TOKEN_BUDGET` in
  `MainActivity.kt` before changing it.
- A picked model only gets persisted to `SharedPreferences` **after** it
  loads successfully (see `onModelSelected` in `MainActivity.kt`). Don't
  move the persist call earlier, a bad file would get stuck as the
  permanent selection across restarts (this exact bug was hit and fixed
  once already).

## Prompt evaluation cost (as of 2026-09-19)

On the Pixel 8 Pro with the 3B model, evaluating a ~300-token prompt took 18-30s
while decoding ran ~4 tok/s, so the wait before the first word is prompt
evaluation, not generation. `nativeGenerate` keeps the KV cache for the shared
prompt prefix (`cachedTokens` in `LlmSession`) and only evaluates the new tail;
follow-up turns dropped to ~7s. Do not go back to `llama_kv_cache_clear` per call.
Tried and dropped: streaming the reply into TTS sentence by sentence (LLM and TTS
fight for CPU, so speech stuttered and trailed the text; only saved a few seconds
because decode is short next to prefill) and `use_mmap=false` (prefill got worse).
Remaining levers if the first-word wait is still too long: a shorter persona
prompt (jarvis is ~840 chars) or the 1B model (~3x faster prefill).

## UI layout (as of 2026-09-19)

Modeled on AI chat apps, all in `JarvisScreen.kt`, no icon library (glyphs
are drawn on `Canvas` in `GlyphButton`):
- Top bar: menu button (opens `HistoryDrawer`, a `ModalNavigationDrawer`),
  title, and a sliders button (opens `SettingsSheet`, a `ModalBottomSheet`
  holding the model and voice lists). Both lists are idle-gated.
- Empty conversation: the voice ring is large and centered (hero). Once
  there are turns it shrinks to a dock under the transcript. `MicControl`
  takes a `diameter` and scales its strokes with it.
- Transcript: Jarvis replies are plain text with an accent hairline; user
  turns are right-aligned bubbles. Each turn has a small timing footnote.
- Warm-up still shows the telemetry row and the Breakout game.
Verify UI changes on-device with screencap: `adb shell screencap -p
/sdcard/x.png` then `adb pull` (PowerShell `>` redirection corrupts PNGs).

## Tool calling

`Tools.kt` (pure: prompt text, lenient JSON parse, spoken-email cleanup) and
`ToolRunner.kt` (Android side: time, battery, timer via `AlarmClock`, email via a
`mailto:` compose intent the user must send). `askJarvis` in `MainActivity.kt`
routes the utterance through `Tools.route` (keyword/regex; also web search via Wikipedia, `WebSearch.kt`) BEFORE the LLM; a match runs the tool and speaks its result, no LLM call. Earlier LLM-emitted JSON calling was tried and dropped: the 1B model ignored the format and invented answers.
second, spoken reply from the result. No llama.cpp grammar is used: a 1B model may
emit malformed calls, so anything unparseable is spoken as ordinary text. Add a tool
by extending `Tools.KNOWN`/`Tools.PROMPT` and `ToolRunner.run`. Not yet exercised
on-device.

## Known state / backlog (as of 2026-09-19)

Working: STT, LLM (now with recent-conversation-history context, see above),
TTS, VAD auto-stop recording, parallel engine warm-up with real/approximate
load-progress UI, LLM model switching, pause/resume for TTS playback, model
download-on-first-launch for STT+LLM, a "Stop" control that aborts an
in-flight listen/think/speak turn, session history (auto-save to local JSON,
"New conversation" and the saved list in the side drawer to start fresh or resume/delete a past
conversation, see README's "Session history"), ktlint + JaCoCo wired up, 64
passing JUnit tests (`VoicePipeline`, `SilenceDetector`, `MiniJson`,
`SessionCodec`, `SessionStore`, `Markdown`, `ModelManager` logic only,
anything Android-framework- or Compose-coupled has 0% coverage by design,
since instrumented/Robolectric tests were explicitly deferred in favor of
fast local-only JUnit).

Not done / open follow-ups:
- Session history is capped at `SessionStore.MAX_SESSIONS` (200); `create()`
  prunes the oldest-updated session past the cap (added 2026-09-21, unit
  tested, not yet exercised on-device since 200 sessions is impractical to
  reach by hand). The history drawer was opened and screenshotted on the
  Pixel 8 Pro on 2026-09-19 (list and layout render correctly); resume/delete
  taps were not exercised.
- TTS models not downloadable (needs a hosting decision from the user).
- No release/signing config, only debug builds exist. Fine for sideloading
  to a friend; would need a keystore + `signingConfig` for anything wider.
- No wake word, no persistent background service, one-shot tap-to-talk
  only.
- No instrumented UI tests for `MainActivity`/`JarvisScreen`.
- LLM generation speed has shown thermal-throttling-driven variance (0.9 to
  4.6 tok/s on identical prompts) after long back-to-back native rebuild
  sessions, not reproduced as a cold-device baseline issue. If it recurs,
  check `adb shell dumpsys thermalservice` for sensor throttle status
  before assuming a code regression.

## TODO (as of 2026-09-19)

Roughly in priority order. Pick from the top unless told otherwise.

1. ~~Downloaded models have no integrity check.~~ Done 2026-09-19:
   `ModelDownloader.download()` now takes an `expectedSha256` param, hashes
   the `.part` file as it streams, and only renames to the final name on a
   match (mismatch deletes the `.part` and throws). `ModelManager` carries
   `STT_SHA256`/`DEFAULT_LLM_SHA256`, sourced from HuggingFace's
   `x-linked-etag` header, not a local computation. See the Model strategy
   section above.
2. ~~No crash resilience around the native layer.~~ Done 2026-09-19 (partly):
   `nativeInit` in the LLM, STT and TTS JNI bridges now catches C++
   exceptions (e.g. `std::bad_alloc`) and returns 0, which Kotlin already
   reports as a load failure, instead of aborting the process. Also added
   `ModelManager.isLowMemoryDevice` (<= 3GB total RAM) driving a dismissible
   advisory banner at warm-up. A true native segfault/SIGABRT still can't be
   caught; no crash reporting was added. Unverified: the banner and the
   catch paths were never exercised on-device (only compiled, launched, and
   checked for crashes in logcat).
3. **No CI.** `ktlintCheck` and `testDebugUnitTest` both run in seconds; add
   a GitHub Actions workflow that runs them on push/PR so "you are the CI"
   (see Build/test/lint above) stops being literally true.
4. ~~Session history has no pruning/size cap.~~ Done 2026-09-21: `SessionStore`
   caps saved sessions at `MAX_SESSIONS` (200), pruning the oldest-updated one
   past the cap on `create()`. No rename UI still (carried over).
5. Session data (`SessionStore`) is unencrypted, unbounded JSON on disk --
   worth a size cap and, if this is ever used for anything sensitive, an
   at-rest encryption pass.
6. ~~No Wi-Fi-only guard before the first-launch download.~~ Done
   2026-09-19: `MainActivity.warmUp()` shows a "Not on Wi-Fi" dialog
   (Download anyway / Wait for Wi-Fi) with the combined pending size when the
   active network is metered; the lazy load paths honor the same gate.
   Fails open if network state can't be read. Dialog not yet seen on-device
   (Wi-Fi is unmetered and models already present on the test phone).
7. No wake word, no persistent background service, one-shot tap-to-talk
   only (carried over).
8. No instrumented UI tests for `MainActivity`/`JarvisScreen` (carried
   over; deferred by design so far in favor of fast local-only JUnit).
9. No release/signing config, only debug builds exist (carried over; fine
    for sideloading to a friend, needed for anything wider).
10. TTS models not downloadable, needs a hosting decision from the user
    (carried over).
