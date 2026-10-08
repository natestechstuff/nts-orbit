# NTS Orbit

An AI assistant for Android that runs **on your phone** or talks to **your own AI server**. No accounts, no cloud, no tracking. Made by **Nate's Tech Stuff**.

![NTS Orbit screens](docs/screens.png)

> **Heads up: NTS Orbit is in development.** Things will change and there are bugs. Issues and ideas are welcome.

## Features

- **On-device chat.** Load a `.gguf` model and it runs fully offline on the phone (llama.cpp, arm64), streamed and read out loud sentence by sentence.
- **Server mode.** Chat with an AI server you run on your computer:
  - **Ollama** (native API, default port 11434)
  - **LM Studio** (:1234), **llama.cpp server** (:8080), **LocalAI**, **KoboldCpp**, **Jan**, **text-generation-webui**, **vLLM**, or anything **OpenAI-compatible** (`/v1/models` + `/v1/chat/completions`)
  - auto-detect, optional API key, model picker, test-connection button, several saved servers
  - same Wi-Fi, or over a VPN like Tailscale (plain `http://` to the address you type in is allowed)
- **Voice.** Tap the mic or go hands-free. Speech-to-text and the voice use your phone's own engines.
- **Phone actions.** "turn on the flashlight", "set a timer for 5 minutes", "set an alarm for 7am", "open YouTube", time/date, battery. Simple commands run instantly; the on-device model can also call these as tools.
- Quick Settings tile and a home-screen widget for one-tap talk.

What it does **not** do: no texting, no SMS, no reading notifications or contacts. The only network connections it makes are to servers you add yourself.

## Install

1. Go to **[Releases](../../releases)** and download `nts-orbit-public-1.0.0.apk` on your phone.
2. Open it and allow "install unknown apps" for your browser/files app when Android asks.
3. Needs Android 8.0+ on a **64-bit ARM** phone (almost every phone from the last few years).

Package name: `com.natestechstuff.orbit`.

## Get the model

For on-device mode you need a GGUF model file:

1. Download the NTS Orbit model `.gguf` from the same **[Release](../../releases)** (about 1 GB, use Wi-Fi).
2. In the app: **Settings → on-device model → pick .gguf file**, choose the download, then tap **▶ test**.

Any other small chat GGUF works too (for example a Qwen2.5 1.5B/3B Instruct `Q4_K_M`). Bigger models need more RAM: 1.5B runs well on most phones with 6 GB+.

Prefer your computer's GPU? Skip the download, run Ollama or LM Studio on your PC, and add it under **Server**.

**About the model:** the NTS Orbit model is a fine-tune of [Qwen2.5-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) (Apache-2.0). The base model's license applies to the weights. Its safety training is **best-effort, not a guarantee**: small models can still be wrong, make things up, or answer things they shouldn't. Double-check anything important.

## Build it yourself

You need JDK 17, the Android SDK (platform 34) and **NDK 27.2.12479018** + CMake 3.22.1 (install both from Android Studio's SDK Manager).

```bash
git clone https://github.com/natestechstuff/nts-orbit.git
cd nts-orbit
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # your SDK path
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

llama.cpp is downloaded automatically at the pinned commit the app was tested with. To use a local checkout instead: `./gradlew assembleRelease -PllamaCppDir=/path/to/llama.cpp`.

`assembleRelease` signs with your local Android **debug key** so the APK installs straight away. An APK you build has a different signature from the one in Releases, so uninstall one before installing the other. For a real store release, set up your own release keystore (and never commit it).

### Tests

```bash
./gradlew testDebugUnitTest
# stream a real reply from a local Ollama too:
./gradlew testDebugUnitTest -PrealServer=http://127.0.0.1:11434 -PrealModel=qwen2.5:0.5b
# render the screens to PNGs (Robolectric):
./gradlew testDebugUnitTest -Pscreenshots=/tmp/shots
```

### Project layout

```
app/src/main/java/…   the app (plain Android framework, no libraries)
  ServerClient         Ollama + OpenAI-compatible streaming client
  ServerStore          saved servers
  LocalBrain, LlamaBridge   on-device model (JNI → llama.cpp)
  ActionRouter, ToolCalls, ToolDispatcher, PhoneTools   phone actions / tool calling
app/src/main/cpp/     JNI glue for llama.cpp
app/src/test/         unit tests (JUnit + Robolectric, mock HTTP servers)
```

The Java package is `com.natestechstuff.jarvis` (the project's original codename); the app id is `com.natestechstuff.orbit`.

## License

NTS Orbit is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
Copyright (C) 2026 Nate's Tech Stuff.

Third-party parts keep their own licenses: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (llama.cpp/ggml: MIT, fonts: SIL OFL 1.1, model weights: Qwen2.5 Apache-2.0).
