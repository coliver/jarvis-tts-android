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
a violation), don't "fix" that by renaming Composables.

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
`LLAMA_CMAKE_ARGS`, it does not inherit the parent's forced build type.

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

## VAD auto-stop tuning (as of 2026-09-21)

`SilenceDetector` (`VoicePipeline.kt`) used to gate speech-vs-silence on a
single fixed RMS threshold (`SILENCE_RMS_THRESHOLD = 400.0` in
`MainActivity.kt`). That broke down in a real (non-silent) room: measured
on-device with office background chatter at conversational volume, ambient
RMS alone ran 1600-5100, comparable to actual speech and far above 400, so
the "gone quiet" clock never armed and recording ran to the 15s
`MAX_RECORD_SECONDS` cap every time. A fixed threshold fundamentally can't
work across rooms since "quiet" and "loud" are relative to the space, not
an absolute PCM level.

`SilenceDetector` now calibrates instead of using a fixed cutoff: the first
3 chunks (300ms) seed a noise-floor estimate from their minimum RMS, and
audio has to be `speechMultiplier` (1.8x) louder than that floor to count
as speech; the floor keeps drifting toward the quietest non-speech chunks
afterward to track a background level that changes over a long recording.
Constructor is now `SilenceDetector(minSpeechMs, silenceHangMs,
speechMultiplier = 1.8)`, no threshold parameter. Verified live on the
Pixel 8 Pro with real office background noise (the same environment that
originally measured 1600-5100 RMS): recording now stops ~1s after speech
ends (6.2s total for a one-sentence test) instead of hitting the 15s cap,
with the transcript still coming through correctly.

## UI layout (as of 2026-09-21)

Modeled on AI chat apps, all in `JarvisScreen.kt`, no icon library (glyphs
are drawn on `Canvas` in `GlyphButton`, and the idle mic ring's microphone
icon is drawn the same way in `drawMicGlyph`):
- Top bar: menu button (opens `HistoryDrawer`, a `ModalNavigationDrawer`),
  title, and a sliders button (opens `SettingsSheet`, a `ModalBottomSheet`
  holding the model and voice lists). Both lists are idle-gated.
- Empty conversation: the voice ring is large and centered (hero,
  `HERO_RING_DP = 232`). Once there are turns it shrinks to a dock under the
  transcript (`DOCK_RING_DP = 148`, raised from 112 on 2026-09-21 since it
  read as too small to tap confidently). `MicControl` takes a `diameter` and
  scales its strokes with it via `scale = diameter.value / 176f`; raising
  `DOCK_RING_DP` past 176 would flip it onto the hero-sized spacing/font
  branches (`scale > 1f` checks), so keep it under that unless those
  branches are revisited too.
- Idle ring: draws a plain mic icon (`drawMicGlyph`, sized up ~35% on
  2026-09-21 after the first pass read as too small at a glance) instead of
  the small center dot the other phases use, and the "Tap to talk" caption underneath
  is left blank (not removed as a row -- see `BELOW_CAPTION_HEIGHT_DP` for
  why fixed-height blank slots exist here rather than conditionally omitting
  the row) so the ring communicates its own affordance without the ring
  visibly resizing across phases. TalkBack still hears "Tap to talk" from
  `micAccessibilityLabel`; only the *drawn* text disappeared, not the
  semantics.
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
conversation, see README's "Session history"), ktlint + JaCoCo wired up, 87
passing JUnit tests as of 2026-09-21 (`VoicePipeline`, `SilenceDetector`, `MiniJson`,
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
- Instrumented UI tests: `JarvisScreenSmokeTest` exists (see TODO item 7
  below) but unverified on-device, blocked on a build-toolchain gap.
- LLM generation speed has shown thermal-throttling-driven variance (0.9 to
  6.6 tok/s on identical prompts) after long back-to-back native rebuild
  and voice-turn sessions, not reproduced as a cold-device baseline issue.
  If it recurs, check `adb shell dumpsys thermalservice` for sensor
  throttle status before assuming a code regression; a clean 5-minute idle
  cooldown reliably bought the best measured run of a session (6.6 tok/s),
  degrading again within one more turn of continued use.
  Investigated 2026-09-21 whether this is fixable rather than just
  endured: `llm_jni_bridge.cpp`'s `nThreads()` already caps work to 4
  threads on the fast+mid cluster (cpu4-8 on this Tensor G3, confirmed via
  `cpufreq/cpuinfo_max_freq`), but nothing pins threads to those specific
  cores. Tried adding `sched_setaffinity` pinning to cpu4-8 -- verified via
  `/proc/<pid>/task/*/status` that the pin itself took correctly, but
  measured tok/s got WORSE (0.9-4.4 vs an unpinned 3.2-6.6 baseline),
  likely because confining every turn's compute to the same fixed 5 cores
  concentrates heat there instead of letting the OS spread load and let
  hot cores cool. Reverted; don't reintroduce without a controlled
  cold-device A/B (not back-to-back turns on an already-warm phone).
  Separately added `+i8mm` to the `-march=` compile flags (`CMakeLists.txt`,
  both the top-level flags and the ones passed into llama.cpp's
  ExternalProject build) -- Tensor G3's cores are all ARMv9.0-A, which
  mandates it, and ggml has dedicated fast kernels for it on K-quant
  formats like this project's Q4_K_M models. Compiles clean; performance
  impact unverified, the device was too thermally loaded from the same
  session's testing to get a clean read.

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
3. ~~No CI.~~ Done (added before 2026-09-19, `.github/workflows/ci.yml`):
   runs `ktlintCheck`, `testDebugUnitTest`, `assembleDebug` on push to `main`
   and on PRs; caches `app/.cxx`; uploads the debug APK (voiceless, since
   `assets/voices/` is gitignored) and lint/test reports as artifacts. Went
   red for two commits (2026-09-21, stray ktlint violations in
   `JarvisScreen.kt` from the accessibility pass) and is green again as of
   `e73d2f5`. "You are the CI" (see Build/test/lint above) is still true in
   spirit, run the checks locally too, don't rely on the push round-trip to
   catch it first.
4. ~~Session history has no pruning/size cap.~~ Done 2026-09-21: `SessionStore`
   caps saved sessions at `MAX_SESSIONS` (200), pruning the oldest-updated one
   past the cap on `create()`. No rename UI still (carried over).
5. ~~Session data (`SessionStore`) is unencrypted...~~ Done 2026-09-21:
   `SessionStore` now takes a `SessionCipher` (`SessionCipher.kt`) and writes
   sessions through it; `MainActivity` wires in `AndroidKeystoreSessionCipher`
   (AES-256-GCM, key generated inside the Android Keystore, never exported).
   The default `PlaintextSessionCipher` keeps `SessionStore` constructible
   under plain JUnit, no Keystore available there. `readSession()` falls back
   to parsing raw UTF-8 JSON if decryption fails, so sessions written before
   this change still load; they're rewritten encrypted on their next `update`/
   `create`, not proactively migrated, so an old session that's never resumed
   again stays plaintext on disk indefinitely. Verified live on the Pixel 8
   Pro: a pre-existing plaintext session loaded and resumed correctly through
   the fallback path, and a fresh voice turn produced a session file that's
   opaque ciphertext on disk (checked with `od`, no readable JSON) yet loads
   back correctly through the app, confirming `AndroidKeystoreSessionCipher`'s
   real Keystore round-trip works on-device, not just under the unit test's
   fake `SessionCipher`.
6. ~~No Wi-Fi-only guard before the first-launch download.~~ Done
   2026-09-19: `MainActivity.warmUp()` shows a "Not on Wi-Fi" dialog
   (Download anyway / Wait for Wi-Fi) with the combined pending size when the
   active network is metered; the lazy load paths honor the same gate.
   Fails open if network state can't be read. Dialog not yet seen on-device
   (Wi-Fi is unmetered and models already present on the test phone).
7. Instrumented UI tests: started 2026-09-21, a smoke test
   (`JarvisScreenSmokeTest`, `app/src/androidTest`) covering the idle
   mic-tap and history-drawer-open flows against `JarvisScreen` directly
   (not `MainActivity`, so it doesn't touch the native STT/LLM/TTS
   pipeline). Compiles and installs, but unverified on-device: the test
   phone runs Android API 37, ahead of any released version, and
   Compose's test harness (pinned by the app's compose-bom 2024.09.00)
   can't find the composed UI on it ("No compose hierarchies found")
   even after forcing `espresso-core` to 3.7.0 to get past an earlier
   `InputManager.getInstance()` reflection failure on the same device.
   The fix needs a much newer compose-bom, which AGP's consistent-
   resolution rule forces onto the whole app, which in turn needs
   compileSdk 37 and AGP 9.1.0+ (currently 8.5.2) -- a real upgrade
   project of its own, not a test-only change. Left as-is (should run
   fine on a standard-API emulator or CI) rather than bundling that
   upgrade in here.
8. No release/signing config, only debug builds exist (carried over; fine
    for sideloading to a friend, needed for anything wider).
9. TTS models not downloadable, needs a hosting decision from the user
    (carried over).
10. No wake word, no persistent background service, one-shot tap-to-talk
    only (carried over; moved to the bottom 2026-09-21 — real
    architectural lift, foreground-service permissions + mic-capture
    lifecycle + a separate wake-word model, and nothing's demonstrated a
    need for it yet over plain tap-to-talk).
