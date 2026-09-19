# Changelog

All notable changes to this project, newest first. Dates are commit dates.

## 2026-09-19

### Added
- Delete-all option in the History menu, with a confirmation dialog.
- Voice tools: time, battery, timer, email draft and Wikipedia search.
- Low-RAM warning, and a prompt before downloading models off Wi-Fi.
- SHA-256 verification of downloaded models before they are accepted.
- CI workflow, and documentation for sharing builds via GitHub Releases.

### Changed
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
