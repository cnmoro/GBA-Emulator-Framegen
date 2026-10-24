#pragma once
// GBA emulation (mGBA core) running on its own thread, with audio (AAudio) and video
// handed to the LSFG render loop as integer-upscaled AHardwareBuffers.

#include <cstdint>
#include <string>

namespace gbafg {

bool emuLoadRom(const std::string &romPath, const std::string &savePath);
void emuUnload();

// Video output: frames are upscaled by `scale` (nearest) before frame generation.
// `framegenRunning` tells whether the render loop is up (frames are pushed to it).
void emuSetVideoScale(int scale);
void emuSetRenderLoopActive(bool active);
void emuSetWideWidth(int w);

void emuStart();      // starts/resumes the emulation thread
void emuPause(bool paused);
void emuStop();

void emuSetKeys(uint32_t keys);              // GBA key bitmask (mGBA GBAKey order)
void emuSetPadding(int w, int h);
void emuSetFrameRate(double fps); // 0 = native GBA rate (59.7275)
void emuSetFastForward(bool on, int speed);  // speed: 2..8 (x real time), 0 = unlimited
void emuSetAudioEnabled(bool on);

bool emuSaveState(const std::string &path);
bool emuLoadState(const std::string &path);
void emuReset();

// Last frame as RGBA8888 240x160 (for state thumbnails); returns false if none.
bool emuCopyLastFrame(uint32_t *dst240x160);

// Stats for the HUD.
double emuFps();
std::string emuAudioStatus();

} // namespace gbafg
