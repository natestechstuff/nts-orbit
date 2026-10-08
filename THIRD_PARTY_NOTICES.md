# Third-party notices

NTS Orbit is licensed under GPL-3.0 (see LICENSE). It includes or builds against the following, each under its own license. All of these licenses are compatible with GPL-3.0.

## llama.cpp / ggml (MIT)

https://github.com/ggml-org/llama.cpp, pinned at commit d81235049384534c167caea52b85a694f6103d14. Fetched at build time and compiled into the app's native libraries (`libllama.so`, `libggml*.so`).

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Fonts (SIL Open Font License 1.1)

- **DM Mono**: Copyright 2020 The DM Mono Project Authors (https://www.github.com/googlefonts/dm-mono)
- **Space Grotesk**: Copyright 2020 The Space Grotesk Project Authors (https://github.com/floriankarsten/space-grotesk)

Bundled in `app/src/main/assets/fonts/`. Full license text: `app/src/main/assets/fonts/OFL.txt`.

## Model weights (not in this repository)

The NTS Orbit `.gguf` model attached to Releases is a fine-tune of **Qwen2.5-1.5B-Instruct** by the Qwen team (Alibaba Cloud), licensed under the **Apache License 2.0** (https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct). The base model's license applies to the weights. The GPL-3.0 license of this repository covers the app's source code, not the model weights.

## Test-only dependencies (not shipped in the app)

JUnit 4 (EPL-1.0), org.json (Public Domain), Robolectric (MIT).
