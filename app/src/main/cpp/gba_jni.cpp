// JNI bridge for com.carlo.gbafg.Native: emulator + LSFG render loop.
#include "gba_emu.hpp"
#include "android_shader_loader.hpp"
#include "android_vk_probe.hpp"
#include "lsfg_render_loop.hpp"
#include "crash_reporter.hpp"

#include <android/native_window_jni.h>
#include <jni.h>

#include <string>

namespace {
std::string str(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
} // namespace

#define FN(name) Java_com_carlo_gbafg_Native_##name

namespace lsfg_android { extern const char *g_fp32_subdir; }
extern float lsfg_debug_t;
extern bool lsfg_robust_access;      // framegen device.cpp
extern bool lsfg_serialize_dispatches; // framegen commandbuffer.cpp // framegen generate.cpp (diagnostics)

extern "C" {

JNIEXPORT jint JNICALL FN(extractShaders)(JNIEnv *env, jclass, jstring dll, jstring cache) {
    return lsfg_android::extract_dll_to_spirv(str(env, dll), str(env, cache));
}

JNIEXPORT jint JNICALL FN(probeShaders)(JNIEnv *env, jclass, jstring cache) {
    return lsfg_android::probe_shaders_on_device(str(env, cache));
}

JNIEXPORT jboolean JNICALL FN(shadersPresent)(JNIEnv *env, jclass, jstring cache) {
    return lsfg_android::load_cached_spirv(str(env, cache), 255).empty() ? JNI_FALSE : JNI_TRUE;
}

// Returns 0 = frame generation running, 1 = running without frame generation
// (no shaders / unsupported GPU: frames are shown as they are), < 0 = error.
JNIEXPORT jint JNICALL FN(initRenderLoop)(JNIEnv *env, jclass, jstring cache, jint width, jint height,
                                          jint multiplier, jfloat flowScale, jboolean performance,
                                          jboolean fp16, jint targetFpsCap) {
    // LSFG builds a 7-level flow pyramid with floor(x >> i) sizes and further halves the
    // coarse levels with ceil((x + 1) >> 1). Unless the flow extent is a multiple of 256 those
    // levels no longer line up with the picture, and the error grows toward the right/bottom
    // edges (the scenery there wobbles while the camera moves). So the frame handed to LSFG is
    // padded (edge pixels repeated) until every level is exact, and only the content is shown.
    const float userFlow = (flowScale >= 0.25f && flowScale <= 1.0f) ? flowScale : 1.0f;
    const float inv = 1.0f / userFlow;
    auto pad = [inv](uint32_t v) {
        for (uint32_t p = v; p < v * 4 + 1024; ++p) {
            const uint32_t fe = static_cast<uint32_t>(static_cast<float>(p) / inv);
            if (fe > 0 && fe % 256 == 0) return p;
        }
        return v;
    };
    const uint32_t pw = pad((uint32_t)width), ph = pad((uint32_t)height);
    lsfg_android::gbafgSetContentExtent((uint32_t)width, (uint32_t)height);
    gbafg::emuSetPadding((int)pw, (int)ph);
    lsfg_android::RenderLoopConfig cfg{};
    cfg.width = pw;
    cfg.height = ph;
    cfg.multiplier = multiplier;
    cfg.flowScale = flowScale;
    cfg.performance = performance == JNI_TRUE;
    cfg.hdr = false;
    cfg.antiArtifacts = false;
    cfg.framegenFp16 = fp16 == JNI_TRUE;
    cfg.npuPostProcessing = false;
    cfg.npuUpscaleFactor = 1;
    cfg.npuRadius = 1.0f;
    cfg.cpuPostProcessing = false;
    cfg.cpuSaturation = 0.5f;
    cfg.gpuPostProcessing = false;
    cfg.gpuStage = 1;
    cfg.gpuUpscaleFactor = 1.0f;
    cfg.targetFpsCap = targetFpsCap;
    cfg.emaAlpha = 0.125f;
    cfg.outlierRatio = 4.0f;
    cfg.vsyncSlackMs = 2.0f;
    cfg.queueDepth = 3;
    const std::string c = str(env, cache);
    const int rc = lsfg_android::initRenderLoop(c.c_str(), cfg);
    gbafg::emuSetRenderLoopActive(rc >= 0);
    return rc;
}

JNIEXPORT void JNICALL FN(shutdownRenderLoop)(JNIEnv *, jclass) {
    gbafg::emuSetRenderLoopActive(false);
    lsfg_android::shutdownRenderLoop();
}

JNIEXPORT void JNICALL FN(setSurface)(JNIEnv *env, jclass, jobject surface, jint w, jint h) {
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    lsfg_android::setOutputSurface(win, (uint32_t)w, (uint32_t)h);
    if (win) ANativeWindow_release(win); // the render loop holds its own reference
}

JNIEXPORT void JNICALL FN(setBypass)(JNIEnv *, jclass, jboolean on) { lsfg_android::setBypass(on == JNI_TRUE); }

JNIEXPORT void JNICALL FN(setVsyncPeriodNs)(JNIEnv *, jclass, jlong ns) { lsfg_android::setVsyncPeriodNs(ns); }

JNIEXPORT jlong JNICALL FN(postedFrames)(JNIEnv *, jclass) { return (jlong)lsfg_android::getPostedFrameCount(); }

JNIEXPORT jlong JNICALL FN(generatedFrames)(JNIEnv *, jclass) { return (jlong)lsfg_android::getGeneratedFrameCount(); }

JNIEXPORT jboolean JNICALL FN(loadRom)(JNIEnv *env, jclass, jstring rom, jstring sav) {
    return gbafg::emuLoadRom(str(env, rom), str(env, sav)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(unload)(JNIEnv *, jclass) { gbafg::emuUnload(); }
JNIEXPORT void JNICALL FN(setVideoScale)(JNIEnv *, jclass, jint s) { gbafg::emuSetVideoScale(s); }
JNIEXPORT void JNICALL FN(setWideWidth)(JNIEnv *, jclass, jint w) { gbafg::emuSetWideWidth(w); }
JNIEXPORT void JNICALL FN(start)(JNIEnv *, jclass) { gbafg::emuStart(); }
JNIEXPORT void JNICALL FN(pause)(JNIEnv *, jclass, jboolean p) { gbafg::emuPause(p == JNI_TRUE); }
JNIEXPORT void JNICALL FN(setKeys)(JNIEnv *, jclass, jint k) { gbafg::emuSetKeys((uint32_t)k); }
JNIEXPORT void JNICALL FN(setFrameRate)(JNIEnv *, jclass, jdouble fps) { gbafg::emuSetFrameRate(fps); }

JNIEXPORT void JNICALL FN(setFastForward)(JNIEnv *, jclass, jboolean on, jint speed) { gbafg::emuSetFastForward(on == JNI_TRUE, speed); }
JNIEXPORT void JNICALL FN(setAudioEnabled)(JNIEnv *, jclass, jboolean on) { gbafg::emuSetAudioEnabled(on == JNI_TRUE); }
JNIEXPORT jboolean JNICALL FN(saveState)(JNIEnv *env, jclass, jstring p) { return gbafg::emuSaveState(str(env, p)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jboolean JNICALL FN(loadState)(JNIEnv *env, jclass, jstring p) { return gbafg::emuLoadState(str(env, p)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL FN(reset)(JNIEnv *, jclass) { gbafg::emuReset(); }
JNIEXPORT jdouble JNICALL FN(emuFps)(JNIEnv *, jclass) { return gbafg::emuFps(); }

JNIEXPORT jboolean JNICALL FN(copyLastFrame)(JNIEnv *env, jclass, jintArray out) {
    if (!out || env->GetArrayLength(out) < 240 * 160) return JNI_FALSE;
    jint *p = env->GetIntArrayElements(out, nullptr);
    const bool ok = gbafg::emuCopyLastFrame(reinterpret_cast<uint32_t *>(p));
    env->ReleaseIntArrayElements(out, p, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(initLog)(JNIEnv *env, jclass, jstring crash, jstring log) {
    lsfg_android::init_crash_reporter(str(env, crash), str(env, log));
}

JNIEXPORT jstring JNICALL FN(nativeLog)(JNIEnv *env, jclass) {
    std::string s = lsfg_android::ring_dump();
    if (s.size() > 60000) s = s.substr(s.size() - 60000);
    for (char &c : s) if ((unsigned char)c >= 0x80 || c == 0) c = '?';
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL FN(diagnostics)(JNIEnv *env, jclass) {
    int64_t p[6] = {0};
    std::string s = gbafg::emuAudioStatus();
    char buf[256];
    if (lsfg_android::getProfileWindowNs(p, 6) == 6 && p[5] > 0) {
        snprintf(buf, sizeof buf, "\nfg ms/frame: copy %.1f present %.1f wait %.1f blit %.1f total %.1f queue %.1f lat %.1f",
                 p[0] / 1e6 / p[5], p[1] / 1e6 / p[5], p[2] / 1e6 / p[5], p[3] / 1e6 / p[5], p[4] / 1e6 / p[5],
                 lsfg_android::getAverageQueueMs(), lsfg_android::getAverageLatencyMs());
        s += buf;
    } else {
        s += "\nfg: no profile window yet";
    }
    s += "\n" + lsfg_android::gbafgOutputStats();
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL FN(startCapture)(JNIEnv *, jclass, jint n) { lsfg_android::gbafgStartCapture(n); }

JNIEXPORT jint JNICALL FN(dumpCount)(JNIEnv *, jclass) { return lsfg_android::gbafgDumpCount(); }

JNIEXPORT jint JNICALL FN(dumpCapIndex)(JNIEnv *, jclass) { return lsfg_android::gbafgDumpCapIndex(); }

JNIEXPORT jstring JNICALL FN(dumpName)(JNIEnv *env, jclass, jint i) {
    std::string n; std::vector<uint8_t> d;
    if (!lsfg_android::gbafgDumpGet(i, n, d)) return nullptr;
    return env->NewStringUTF(n.c_str());
}

JNIEXPORT jbyteArray JNICALL FN(dumpData)(JNIEnv *env, jclass, jint i) {
    std::string n; std::vector<uint8_t> d;
    if (!lsfg_android::gbafgDumpGet(i, n, d)) return nullptr;
    jbyteArray a = env->NewByteArray((jsize)d.size());
    env->SetByteArrayRegion(a, 0, (jsize)d.size(), reinterpret_cast<const jbyte *>(d.data()));
    return a;
}

JNIEXPORT jint JNICALL FN(captureCount)(JNIEnv *, jclass) { return lsfg_android::gbafgCaptureCount(); }

// fills info[4] = {w, h, tag (0 real, n generated), time in 1/100 ms}; returns ARGB pixels or null
JNIEXPORT jintArray JNICALL FN(captureFrame)(JNIEnv *env, jclass, jint i, jintArray info) {
    int32_t inf[4];
    std::vector<uint32_t> px;
    if (!lsfg_android::gbafgCaptureFrame(i, inf, px)) return nullptr;
    env->SetIntArrayRegion(info, 0, 4, inf);
    jintArray a = env->NewIntArray((jsize)px.size());
    env->SetIntArrayRegion(a, 0, (jsize)px.size(), reinterpret_cast<const jint *>(px.data()));
    return a;
}

JNIEXPORT void JNICALL FN(setMaliFixes)(JNIEnv *, jclass, jboolean robust, jboolean serialize) {
    lsfg_robust_access = robust == JNI_TRUE;
    lsfg_serialize_dispatches = serialize == JNI_TRUE;
}

JNIEXPORT void JNICALL FN(setFp32Subdir)(JNIEnv *env, jclass, jstring dir) {
    static std::string keep;
    keep = str(env, dir);
    lsfg_android::g_fp32_subdir = keep.c_str();
}

JNIEXPORT void JNICALL FN(setDebugT)(JNIEnv *, jclass, jfloat t) { lsfg_debug_t = t; }

JNIEXPORT void JNICALL FN(setForceDxbc)(JNIEnv *, jclass, jboolean on) { lsfg_android::gbafgSetForceDxbc(on); }

JNIEXPORT void JNICALL FN(setForceCpuOutput)(JNIEnv *, jclass, jboolean on) { lsfg_android::gbafgSetForceCpuOutput(on); }

} // extern "C"
