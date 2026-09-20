# Third-party notices

## omatts (parnoldx/omatts)

`app/src/main/cpp/omatts.cpp` and `app/src/main/cpp/models/*` are copied from
[parnoldx/omatts](https://github.com/parnoldx/omatts), licensed under the MIT
License (see `LICENSE`).

## Pocket-TTS model weights

The exported ONNX model weights under `app/src/main/assets/models/` derive
from `kyutai-labs/pocket-tts`, licensed CC-BY-4.0. See
`app/src/main/cpp/models/LICENSE-WEIGHTS.txt` for full attribution terms.

## ONNX Runtime

Fetched at build time from Maven Central
(`com.microsoft.onnxruntime:onnxruntime-android`), licensed under the MIT
License.

## SentencePiece

Fetched at build time from `google/sentencepiece`, licensed under the Apache
License 2.0.

## dr_libs

Fetched at build time from `mackron/dr_libs`, public domain / MIT-0.

## whisper.cpp

Fetched at build time from `ggerganov/whisper.cpp`, licensed under the MIT
License.

## llama.cpp

Fetched at build time from `ggerganov/llama.cpp`, licensed under the MIT
License.

## AndroidX and Jetpack Compose

`androidx.core`, `androidx.appcompat`, `androidx.activity`, and the Compose
libraries (`ui`, `ui-graphics`, `material3`) are resolved via Gradle and
licensed under the Apache License 2.0.

## kotlinx.coroutines

Resolved via Gradle from `Kotlin/kotlinx.coroutines`, licensed under the
Apache License 2.0.

## Whisper speech model (downloaded at runtime)

`ggml-base.en-q5_1.bin` is downloaded from Hugging Face
(`ggerganov/whisper.cpp`) on first use, not bundled. The Whisper model
weights are from OpenAI and are licensed under the MIT License.

## Llama 3.2 language model (downloaded at runtime)

`Llama-3.2-3B-Instruct-Q4_K_M.gguf` is downloaded from Hugging Face
(`bartowski/Llama-3.2-3B-Instruct-GGUF`) on first use, not bundled. It is
Meta's Llama 3.2, governed by the Llama 3.2 Community License and Acceptable
Use Policy (https://www.llama.com/llama3_2/license/). It allows commercial
use but is not an OSI open-source license. If you redistribute the model or a
product that includes it, the license requires "Built with Llama" attribution
and inclusion of the license text. Review those terms before distributing.
