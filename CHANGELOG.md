# Changelog

All notable changes to this project, newest first. Dates are commit dates.

## 2026-09-21

### Added
- Session history is now capped at 200 saved conversations; the oldest-updated one is pruned automatically each time a new session is created.

### Fixed
- The transcript used to leave a large empty gap above the docked mic ring during Thinking/Speaking on short conversations; it now sits directly above the ring.
- The "Hold to stop" / "Tap to pause, hold to stop" hint was dimmed further on top of the already-tuned label color, undoing the contrast fix from 2026-09-20; it's back to full label contrast and a touch larger.

## 2026-09-20

### Fixed
- Accessibility: the mic control had no label and, while listening/thinking/speaking, no TalkBack-reachable action at all (its hold-to-stop gesture bypassed Compose's accessibility system entirely). It now announces its phase and exposes a stop/pause action.
- Accessibility: dimmed label text fell just under WCAG AA contrast against the background; lightened slightly to clear it.
- Accessibility: model/voice picker rows and the low-memory warning's dismiss action are now properly announced to screen readers.

## 2026-09-19

### Added
- Delete-all option in the History menu, with a confirmation dialog.
- Voice tools: time, battery, timer, email draft and Wikipedia search.
- Low-RAM warning, and a prompt before downloading models off Wi-Fi.
- SHA-256 verification of downloaded models before they are accepted.
- CI workflow, and documentation for sharing builds via GitHub Releases.

### Changed
- The language model now reuses the unchanged start of the previous prompt (persona and earlier turns), cutting prompt evaluation on follow-up questions from 18 to 30 seconds down to about 7.
- Layout rethought after modern AI chat apps: conversation history moved into a side drawer, model and voice pickers merged into a bottom sheet, Jarvis replies now read as plain text with an accent line while your words sit in a bubble, and the voice ring is large on an empty conversation then shrinks to a bottom dock so the transcript gets more room.
- Default language model upgraded from Llama 3.2 1B to Llama 3.2 3B (~2 GB download) for noticeably better answers; replies are slower. Existing installs keep the 1B file and can switch via the model picker; new downloads fetch the 3B.
- README now shows a demo GIF of a full listen, think and speak cycle.
- README corrected against the code, badges added; third-party notices now cover all dependencies and models.
- Speech model switched from tiny.en to base.en for better accuracy in noise.

### Fixed
- CI failing on every run: the Gradle wrapper jar was git-ignored and is now committed.
- Long chats returning blank replies: the LLM KV cache is now cleared each turn.
- Native init aborting the process on C++ exceptions; they are now caught.

## 2026-09-18

### Added
- Turn cancellation and session history persistence (save, resume, delete).
- Voice picker, redesigned mic control, and markdown rendering in replies.
- Conversation history is now fed into LLM prompts.

### Changed
- Voice personas moved out of code into a JSON asset.
- Reworked the enterprise persona; renamed the data voice sample.

### Fixed
- Several performance bugs.

## 2026-09-17

### Added
- Initial on-device voice assistant: speech-to-text, local LLM and text-to-speech, running on a Pixel 8 Pro.
