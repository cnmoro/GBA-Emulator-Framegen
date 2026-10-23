# GBA FrameGen

A Game Boy Advance emulator for Android (mGBA core) with Lossless Scaling frame generation
(the lsfg-vk pipeline, integrated the way LSFG-Android does it), fed directly with the emulator's frames.
It turns 60 fps GBA games into 120/180/240 fps output on high-refresh displays.

## Features

- mGBA 0.10.5 core, with audio resampled to the locked emulation rate (refresh / k, e.g. exactly 60 fps at 120 Hz)
- Frame generation (LSFG 3.1 / 3.1P) through Vulkan compute, with integer-scaled presentation
- Workaround for a Mali shader-compiler bug (`ConstOffset` texture fetches are folded into coordinates,
  see `app/src/main/cpp/lsfg/spirv_fold_offsets.cpp`) that made the generated frames shake on Mali GPUs
- Games folder via SAF: `.gba` files are imported from it and `.sav` files are mirrored back next to the ROM
- Save states (multiple slots), fast forward, editable on-screen controls, optional FPS / diagnostics HUD
- In-app diagnostic log and a frame-capture tool (zip to Downloads)

## Shaders

The `Lossless.dll` is **not** distributed. Buy Lossless Scaling on Steam, then import your own
`Lossless.dll` in the app (*Import Lossless.dll*); the shaders are extracted on the device and the copy is deleted.

`shaders/shaders_mali.zip` is an optional shader pack (recompiled FP32 SPIR-V sets `fp32r/` and `fp32o/`)
that can be imported with *Import shader pack (.zip)* and selected under *Settings → Shaders*.

## Building

```
./gradlew :app:assembleRelease
```

Requirements: Android SDK 35, NDK 27.0.12077973, CMake 3.22.1. The output targets `arm64-v8a` only.
The repository includes a throwaway signing key (`keystore/gbafg.keystore`, password `android`)
so that builds can update each other; use your own key for anything you publish.

## Licenses

- Emulation: mGBA 0.10.5 (MPL-2.0), `third_party/mgba`
- Frame generation: lsfg-vk / LSFG-Android (MIT), `third_party/lsfg-vk-android`, `app/src/main/cpp/lsfg`
- Lossless Scaling belongs to its respective owner. The DLL is not included; note that `shaders/shaders_mali.zip`
  contains SPIR-V recompiled from its shaders.
