cmake_minimum_required(VERSION 3.22.1)
project(gbafg LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(TP "${CMAKE_SOURCE_DIR}/../../../../third_party")
set(LSFG_ROOT "${TP}/lsfg-vk-android")

# -----------------------------------------------------------------------------
# mGBA core (MPL-2.0), built as a static library without frontends/deps.
# -----------------------------------------------------------------------------
set(LIBMGBA_ONLY ON)
set(M_CORE_GBA ON)
set(M_CORE_GB OFF)
set(ENABLE_SCRIPTING OFF)
set(USE_DEBUGGERS OFF)
add_subdirectory("${TP}/mgba" "${CMAKE_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------------
# lsfg-vk framegen + helpers (MIT), as wired by LSFG-Android.
# -----------------------------------------------------------------------------
add_library(volk STATIC "${LSFG_ROOT}/thirdparty/volk/volk.c")
target_include_directories(volk PUBLIC "${LSFG_ROOT}/thirdparty/volk")
target_compile_definitions(volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR=1)

set(PEPARSE_DIR "${LSFG_ROOT}/thirdparty/pe-parse/pe-parser-library")
add_library(pe-parse STATIC
    "${PEPARSE_DIR}/src/buffer.cpp"
    "${PEPARSE_DIR}/src/parse.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/lsfg/unicode_minimal.cpp"
)
target_include_directories(pe-parse PUBLIC "${PEPARSE_DIR}/include")
target_compile_features(pe-parse PUBLIC cxx_std_17)

add_subdirectory("${LSFG_ROOT}/thirdparty/dxbc" "${CMAKE_BINARY_DIR}/dxbc" EXCLUDE_FROM_ALL)
add_subdirectory("${LSFG_ROOT}/framegen" "${CMAKE_BINARY_DIR}/framegen" EXCLUDE_FROM_ALL)

find_library(log-lib log)
find_library(neuralnetworks-lib neuralnetworks)
find_library(aaudio-lib aaudio)

add_library(gbafg SHARED
    gba_jni.cpp
    gba_emu.cpp
    lsfg/android_shader_loader.cpp
    lsfg/spirv_fold_offsets.cpp
    lsfg/android_vk_probe.cpp
    lsfg/android_vk_session.cpp
    lsfg/ahb_image_bridge.cpp
    lsfg/gpu_postprocess.cpp
    lsfg/lsfg_render_loop.cpp
    lsfg/nnapi_npu.cpp
    lsfg/nnapi_postprocess.cpp
    lsfg/cpu_postprocess.cpp
    lsfg/crash_reporter.cpp
)
target_include_directories(gbafg PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/lsfg")

target_link_libraries(gbafg
    PRIVATE
        mgba
        lsfg-vk-framegen
        dxbc
        pe-parse
        volk
        ${log-lib}
        ${neuralnetworks-lib}
        ${aaudio-lib}
        android
)
