#include "gba_emu.hpp"
#include "lsfg_render_loop.hpp"
#include "crash_reporter.hpp"

extern "C" {
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>
}

#include <aaudio/AAudio.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <thread>
#include <time.h>
#include <vector>

#define LOG_TAG "gbafg-emu"
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_WARN, __VA_ARGS__)

namespace gbafg {
namespace {

constexpr int kW = 240, kH = 160;
constexpr int kAudioRate = 48000;
// GBA: 16777216 Hz / 280896 cycles per frame = 59.7275 fps
constexpr int64_t kGbaFrameNs = (int64_t)(1e9 * 280896.0 / 16777216.0);
// Emulated frame period actually used. Locked to a divisor of the display refresh when one is
// close (120 Hz -> 60.000 fps instead of 59.727), so real and generated frames line up with vsync
// and FIFO never has to repeat a frame (a periodic hitch otherwise).
std::atomic<int64_t> gFrameNs{kGbaFrameNs};
constexpr int kPool = 8;

struct PoolSlot {
    AHardwareBuffer *ahb = nullptr;
    std::atomic<bool> busy{false};
};

struct Emu {
    std::mutex coreMu;                 // guards core (runFrame vs save/load state)
    mCore *core = nullptr;
    std::vector<color_t> video = std::vector<color_t>(256 * 256);
    std::vector<uint32_t> lastFrame = std::vector<uint32_t>(kW * kH);
    std::atomic<bool> haveFrame{false};

    std::thread thread;
    std::mutex stateMu;
    std::condition_variable cv;
    bool running = false;
    bool paused = false;

    std::atomic<uint32_t> keys{0};
    std::atomic<bool> ff{false};
    std::atomic<int> ffSpeed{3};
    std::atomic<bool> audioOn{true};

    // video
    std::mutex poolMu;
    PoolSlot pool[kPool];
    int scale = 3;
    int poolW = 0, poolH = 0;
    int padW = 0, padH = 0; // size handed to the render loop (>= content, see gba_jni.cpp)
    std::atomic<bool> loopActive{false};
    std::vector<uint32_t> rowBuf;

    // audio
    AAudioStream *stream = nullptr;
    std::vector<int16_t> ring = std::vector<int16_t>(16384 * 2);
    std::atomic<uint32_t> rHead{0}, rTail{0}; // in frames
    int16_t scratch[4096 * 2];

    // stats
    std::atomic<double> fps{0};
};

Emu E;
std::atomic<bool> gAudioReopen{false};
std::atomic<int> gAudioOpenErr{0};
std::atomic<uint32_t> gAudioCallbacks{0};
std::atomic<uint32_t> gAudioUnderruns{0};
std::atomic<uint32_t> gPoolDrops{0};
std::atomic<uint64_t> gAudioPushed{0};
std::atomic<int> gAudioPeak{0};
std::atomic<bool> gAudioPrimed{false};
constexpr uint32_t kAudioPrimeFrames = kAudioRate * 50 / 1000; // ~50 ms cushion

// mGBA logs every DMA/BIOS call by default: formatting those every frame costs real CPU
void silentLog(mLogger *, int, enum mLogLevel, const char *, va_list) {}
mLogger sSilentLogger = {silentLog, nullptr};

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ---------------------------------------------------------------------------
// audio ring (single producer: emu thread, single consumer: AAudio callback)
// ---------------------------------------------------------------------------
uint32_t ringFrames() { return (uint32_t)E.ring.size() / 2; }
uint32_t ringFill() { return E.rTail.load() - E.rHead.load(); }

void ringPush(const int16_t *src, uint32_t frames) {
    const uint32_t cap = ringFrames();
    uint32_t tail = E.rTail.load(std::memory_order_relaxed);
    const uint32_t head = E.rHead.load(std::memory_order_acquire);
    if (tail - head + frames > cap) frames = cap - (tail - head); // drop what doesn't fit
    for (uint32_t i = 0; i < frames; ++i) {
        const uint32_t k = (tail + i) % cap;
        E.ring[k * 2] = src[i * 2];
        E.ring[k * 2 + 1] = src[i * 2 + 1];
    }
    E.rTail.store(tail + frames, std::memory_order_release);
}

aaudio_data_callback_result_t audioCb(AAudioStream *, void *, void *audioData, int32_t numFrames) {
    gAudioCallbacks.fetch_add(1, std::memory_order_relaxed);
    int16_t *out = static_cast<int16_t *>(audioData);
    const uint32_t cap = ringFrames();
    uint32_t head = E.rHead.load(std::memory_order_relaxed);
    const uint32_t tail = E.rTail.load(std::memory_order_acquire);
    uint32_t avail = tail - head;
    // build a small cushion first (and again after an underrun), so the ~16 ms bursts from the
    // emulator never leave the device starving between frames
    if (!gAudioPrimed.load(std::memory_order_relaxed)) {
        if (avail < kAudioPrimeFrames) {
            std::memset(out, 0, (size_t)numFrames * 4);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        gAudioPrimed.store(true, std::memory_order_relaxed);
    }
    if (avail < (uint32_t)numFrames) {
        gAudioUnderruns.fetch_add(1, std::memory_order_relaxed);
        gAudioPrimed.store(false, std::memory_order_relaxed);
    }
    int32_t i = 0;
    for (; i < numFrames && avail > 0; ++i, --avail, ++head) {
        const uint32_t k = head % cap;
        out[i * 2] = E.ring[k * 2];
        out[i * 2 + 1] = E.ring[k * 2 + 1];
    }
    for (; i < numFrames; ++i) out[i * 2] = out[i * 2 + 1] = 0;
    E.rHead.store(head, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void audioOpen();

void audioErrCb(AAudioStream *, void *, aaudio_result_t err) {
    LOGW("AAudio error %d: reopening", (int)err);
    gAudioReopen = true; // reopen from the emulation thread (not allowed inside the callback)
}

void audioOpen() {
    if (E.stream) return;
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setSampleRate(b, kAudioRate);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, audioCb, nullptr);
    AAudioStreamBuilder_setErrorCallback(b, audioErrCb, nullptr);
    const aaudio_result_t r = AAudioStreamBuilder_openStream(b, &E.stream);
    gAudioOpenErr = r;
    if (r != AAUDIO_OK) {
        LOGW("AAudio open failed: %d", (int)r);
        E.stream = nullptr;
    }
    AAudioStreamBuilder_delete(b);
    if (E.stream) {
        const aaudio_result_t r = AAudioStream_requestStart(E.stream);
        LOGI("AAudio open ok: rate=%d frames/burst=%d start=%d", AAudioStream_getSampleRate(E.stream),
             AAudioStream_getFramesPerBurst(E.stream), (int)r);
    }
}

void audioClose() {
    if (!E.stream) return;
    AAudioStream_requestStop(E.stream);
    AAudioStream_close(E.stream);
    E.stream = nullptr;
}

// Drain the core's blip buffers; keep the ring around ~60 ms with a tiny rate nudge
// (+-0.5 %) so audio never drifts into crackles or growing latency.
void drainAudio(bool keep) {
    blip_t *l = E.core->getAudioChannel(E.core, 0);
    blip_t *r = E.core->getAudioChannel(E.core, 1);
    int avail = blip_samples_avail(l);
    while (avail > 0) {
        int n = avail > 4096 ? 4096 : avail;
        blip_read_samples(l, E.scratch, n, true);
        blip_read_samples(r, E.scratch + 1, n, true);
        if (keep) {
            ringPush(E.scratch, (uint32_t)n);
            gAudioPushed.fetch_add((uint64_t)n, std::memory_order_relaxed);
            int pk = gAudioPeak.load(std::memory_order_relaxed);
            for (int i = 0; i < n * 2; i += 16) {
                const int v = E.scratch[i] < 0 ? -E.scratch[i] : E.scratch[i];
                if (v > pk) pk = v;
            }
            gAudioPeak.store(pk, std::memory_order_relaxed);
        }
        avail -= n;
    }
    const double target = kAudioRate * 0.07;
    const double fill = keep ? (double)ringFill() : target;
    double adj = (target - fill) / target * 0.005;
    if (adj > 0.005) adj = 0.005;
    if (adj < -0.005) adj = -0.005;
    // emulated time runs at kGbaFrameNs/frameNs of real time: scale the output rate so the
    // samples cover exactly the real time that passes
    const double speed = (double)kGbaFrameNs / (double)gFrameNs.load(std::memory_order_relaxed);
    const double rate = kAudioRate / speed * (1.0 + adj);
    blip_set_rates(l, E.core->frequency(E.core), rate);
    blip_set_rates(r, E.core->frequency(E.core), rate);
}

// ---------------------------------------------------------------------------
// video pool
// ---------------------------------------------------------------------------
void freePool() {
    for (auto &s : E.pool) {
        if (s.ahb) AHardwareBuffer_release(s.ahb);
        s.ahb = nullptr;
        s.busy = false;
    }
    E.poolW = E.poolH = 0;
}

bool ensurePool() {
    const int w = std::max(kW * E.scale, E.padW), h = std::max(kH * E.scale, E.padH);
    if (E.poolW == w && E.poolH == h) return true;
    freePool();
    AHardwareBuffer_Desc d{};
    d.width = (uint32_t)w;
    d.height = (uint32_t)h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY
            | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    for (auto &s : E.pool) {
        if (AHardwareBuffer_allocate(&d, &s.ahb) != 0) {
            s.ahb = nullptr;
            LOGW("AHardwareBuffer_allocate %dx%d failed", w, h);
            freePool();
            return false;
        }
    }
    E.poolW = w;
    E.poolH = h;
    E.rowBuf.assign((size_t)w, 0);
    LOGI("video pool %dx%d (content %dx%d)", w, h, kW * E.scale, kH * E.scale);
    return true;
}

void presentFrame() {
    // keep a copy for thumbnails (and notice when nothing changed)
    bool still = E.haveFrame.load();
    for (int y = 0; y < kH; ++y) {
        const color_t *src = E.video.data() + y * 256;
        uint32_t *dst = E.lastFrame.data() + y * kW;
        for (int x = 0; x < kW; ++x) {
            const uint32_t c = (uint32_t)src[x] | 0xFF000000u;
            if (dst[x] != c) {
                still = false;
                dst[x] = c;
            }
        }
    }
    E.haveFrame = true;
    if (!E.loopActive.load()) return;

    std::lock_guard<std::mutex> lk(E.poolMu);
    if (!ensurePool()) return;
    PoolSlot *slot = nullptr;
    for (auto &s : E.pool) {
        if (!s.busy.load()) {
            slot = &s;
            break;
        }
    }
    if (!slot) { gPoolDrops++; return; } // the render loop is behind: drop this frame

    void *ptr = nullptr;
    if (AHardwareBuffer_lock(slot->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &ptr) != 0 || !ptr) return;
    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(slot->ahb, &d);
    const int sc = E.scale, w = E.poolW, cw = kW * sc, ch = kH * sc;
    uint8_t *base = static_cast<uint8_t *>(ptr);
    const size_t rowBytes = (size_t)d.stride * 4;
    for (int y = 0; y < kH; ++y) {
        const uint32_t *src = E.lastFrame.data() + y * kW;
        uint32_t *row = E.rowBuf.data();
        for (int x = 0; x < kW; ++x) {
            const uint32_t c = src[x];
            for (int k = 0; k < sc; ++k) *row++ = c;
        }
        for (int x = cw; x < w; ++x) *row++ = src[kW - 1]; // repeat the right edge into the padding
        for (int k = 0; k < sc; ++k)
            std::memcpy(base + (size_t)(y * sc + k) * rowBytes, E.rowBuf.data(), (size_t)w * 4);
    }
    for (int y = ch; y < E.poolH; ++y) // repeat the bottom edge into the padding
        std::memcpy(base + (size_t)y * rowBytes, base + (size_t)(ch - 1) * rowBytes, (size_t)w * 4);
    AHardwareBuffer_unlock(slot->ahb, nullptr);
    slot->busy = true;
    if (!lsfg_android::pushFrame(slot->ahb, nowNs(), still))
        slot->busy = false; // duplicate frame or loop not running: not taken
}

// ---------------------------------------------------------------------------
// emulation thread
// ---------------------------------------------------------------------------
void threadMain() {
    int64_t deadline = nowNs();
    int64_t fpsT0 = deadline;
    int fpsN = 0;
    while (true) {
        {
            std::unique_lock<std::mutex> lk(E.stateMu);
            E.cv.wait(lk, [] { return !E.running || !E.paused; });
            if (!E.running) break;
        }
        if (gAudioReopen.exchange(false)) {
            audioClose();
            audioOpen();
        }
        const bool ff = E.ff.load();
        const int speed = E.ffSpeed.load();
        const int64_t frameStart = nowNs();
        const int64_t kFrameNs = gFrameNs.load(std::memory_order_relaxed);
        int ran = 0;
        {
            std::lock_guard<std::mutex> lk(E.coreMu);
            if (!E.core) break;
            const int n = ff ? (speed > 0 ? speed : 64) : 1;
            for (int i = 0; i < n; ++i) {
                E.core->setKeys(E.core, E.keys.load());
                E.core->runFrame(E.core);
                drainAudio(!ff && E.audioOn.load());
                ++ran;
                // unlimited fast forward: as many frames as fit in one display frame
                if (ff && speed <= 0 && nowNs() - frameStart > kFrameNs * 9 / 10) break;
            }
        }
        presentFrame();
        fpsN += ran;
        const int64_t t = nowNs();
        if (t - fpsT0 >= 1000000000LL) {
            E.fps = fpsN * 1e9 / (double)(t - fpsT0);
            fpsN = 0;
            fpsT0 = t;
        }
        deadline += kFrameNs;
        if (t - deadline > kFrameNs * 4 || deadline - t > kFrameNs * 4) deadline = t; // resync after a stall
        timespec ts{};
        ts.tv_sec = deadline / 1000000000LL;
        ts.tv_nsec = deadline % 1000000000LL;
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
    }
}

} // namespace

void gbafg_onAhbReleasedImpl(AHardwareBuffer *ahb) {
    for (auto &s : E.pool)
        if (s.ahb == ahb) s.busy = false;
}

bool emuLoadRom(const std::string &romPath, const std::string &savePath) {
    emuStop();
    std::lock_guard<std::mutex> lk(E.coreMu);
    if (E.core) {
        E.core->deinit(E.core);
        E.core = nullptr;
    }
    mLogSetDefaultLogger(&sSilentLogger);
    mCore *core = GBACoreCreate();
    if (!core) return false;
    core->init(core);
    mCoreInitConfig(core, nullptr);
    mCoreConfigSetDefaultValue(&core->config, "idleOptimization", "detect");
    mCoreConfigSetDefaultIntValue(&core->config, "volume", 0x100); // default is 0 = silent
    mCoreLoadConfig(core);
    core->setVideoBuffer(core, E.video.data(), 256);
    core->setAudioBufferSize(core, 2048);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), kAudioRate);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), kAudioRate);
    VFile *rom = VFileOpen(romPath.c_str(), O_RDONLY);
    if (!rom || !core->loadROM(core, rom)) {
        LOGW("loadROM failed: %s", romPath.c_str());
        core->deinit(core);
        return false;
    }
    VFile *sav = VFileOpen(savePath.c_str(), O_CREAT | O_RDWR);
    if (sav) core->loadSave(core, sav);
    core->reset(core);
    E.core = core;
    E.haveFrame = false;
    return true;
}

void emuUnload() {
    emuStop();
    std::lock_guard<std::mutex> lk(E.coreMu);
    if (E.core) {
        E.core->unloadROM(E.core); // flushes the battery save
        E.core->deinit(E.core);
        E.core = nullptr;
    }
}

void emuSetVideoScale(int scale) {
    std::lock_guard<std::mutex> lk(E.poolMu);
    if (scale < 1) scale = 1;
    if (scale > 8) scale = 8;
    if (scale != E.scale) {
        E.scale = scale;
        // buffers still held by the render loop stay alive (it holds its own reference)
        freePool();
    }
}

void emuSetPadding(int w, int h) {
    std::lock_guard<std::mutex> lk(E.poolMu);
    if (w != E.padW || h != E.padH) {
        E.padW = w;
        E.padH = h;
        freePool();
    }
}

void emuSetRenderLoopActive(bool active) { E.loopActive = active; }

void emuStart() {
    std::lock_guard<std::mutex> lk(E.stateMu);
    if (!E.core) return;
    E.paused = false;
    if (!E.running) {
        E.running = true;
        audioOpen();
        E.thread = std::thread(threadMain);
    } else if (E.stream) {
        AAudioStream_requestStart(E.stream);
    } else {
        audioOpen();
    }
    E.cv.notify_all();
}

void emuPause(bool paused) {
    {
        std::lock_guard<std::mutex> lk(E.stateMu);
        E.paused = paused;
    }
    if (E.stream) {
        if (paused) AAudioStream_requestPause(E.stream);
        else AAudioStream_requestStart(E.stream);
    }
    E.cv.notify_all();
}

void emuStop() {
    {
        std::lock_guard<std::mutex> lk(E.stateMu);
        E.running = false;
    }
    E.cv.notify_all();
    if (E.thread.joinable()) E.thread.join();
    audioClose();
}

void emuSetKeys(uint32_t keys) { E.keys = keys; }

void emuSetFrameRate(double fps) {
    if (fps < 50.0 || fps > 70.0) gFrameNs = kGbaFrameNs;
    else gFrameNs = (int64_t)(1e9 / fps);
    LOGI("emulated frame period %lld ns (%.3f fps)", (long long)gFrameNs.load(), 1e9 / (double)gFrameNs.load());
}

void emuSetFastForward(bool on, int speed) {
    E.ff = on;
    E.ffSpeed = speed;
}

void emuSetAudioEnabled(bool on) { E.audioOn = on; }

bool emuSaveState(const std::string &path) {
    std::lock_guard<std::mutex> lk(E.coreMu);
    if (!E.core) return false;
    const size_t n = E.core->stateSize(E.core);
    std::vector<uint8_t> buf(n);
    if (!E.core->saveState(E.core, buf.data())) return false;
    FILE *f = fopen((path + ".tmp").c_str(), "wb");
    if (!f) return false;
    const bool ok = fwrite(buf.data(), 1, n, f) == n;
    fclose(f);
    return ok && rename((path + ".tmp").c_str(), path.c_str()) == 0;
}

bool emuLoadState(const std::string &path) {
    std::lock_guard<std::mutex> lk(E.coreMu);
    if (!E.core) return false;
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return false;
    const size_t n = E.core->stateSize(E.core);
    std::vector<uint8_t> buf(n);
    const bool ok = fread(buf.data(), 1, n, f) == n;
    fclose(f);
    return ok && E.core->loadState(E.core, buf.data());
}

void emuReset() {
    std::lock_guard<std::mutex> lk(E.coreMu);
    if (E.core) E.core->reset(E.core);
}

bool emuCopyLastFrame(uint32_t *dst) {
    if (!E.haveFrame) return false;
    std::memcpy(dst, E.lastFrame.data(), kW * kH * 4);
    return true;
}

double emuFps() { return E.fps.load(); }

} // namespace gbafg

void gbafg_onAhbReleased(AHardwareBuffer *ahb) { gbafg::gbafg_onAhbReleasedImpl(ahb); }

namespace gbafg {
std::string emuAudioStatus() {
    char buf[200];
    int state = E.stream ? (int)AAudioStream_getState(E.stream) : -1;
    snprintf(buf, sizeof buf, "audio: stream=%s state=%d err=%d cb=%u fill=%u pushed=%llu peak=%d xrun=%u | emuDrop=%u",
             E.stream ? "ok" : "none", state, gAudioOpenErr.load(), gAudioCallbacks.load(), ringFill(),
             (unsigned long long)gAudioPushed.load(), gAudioPeak.load(), gAudioUnderruns.load(), gPoolDrops.load());
    return buf;
}
} // namespace gbafg
