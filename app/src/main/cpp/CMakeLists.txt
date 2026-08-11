cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Vendor build config, must be set BEFORE add_subdirectory() ---

# whisper.cpp/ggml default to building several internal pieces
# (ggml-base, ggml, ggml-cpu, whisper) as SEPARATE SHARED LIBRARIES.
# Their CMakeLists mix .c and .cpp sources in the same target, and when
# a target is majority-.c, CMake sometimes links it with the plain C
# driver instead of the C++ driver — which silently drops libc++, and
# you get "undefined symbol: operator new / __cxa_throw / std::string..."
# at link time. Forcing static libs sidesteps the bug entirely: static
# libs are just archived .o files, no linking happens until our own
# whisper_jni_bridge target links everything at the end (correctly,
# since its only direct source is a .cpp file).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Avoid a dynamic dependency on libomp.so, which the NDK doesn't bundle
# on-device by default — without this, ggml links against it and you'd
# get UnsatisfiedLinkError at runtime unless you manually copy libomp.so
# into jniLibs/. Falls back to plain pthreads instead.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Cross-compiling for Android — don't let ggml try to autodetect and
# target the host machine's CPU features.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# ggml has a "dynamic backend loading" feature: at runtime it scans a
# directory and dlopen()s different backend plugins (CPU variants, GPU
# backends, etc.) as separate .so files. That machinery depends on
# std::filesystem, dlopen, and exceptions much more heavily than a normal
# static build — it's what's producing the current undefined-symbol errors
# in ggml-backend-reg.cpp / ggml-backend-dl.cpp. It's also irrelevant here:
# we want a single, self-contained static CPU backend baked into our .so,
# not a plugin directory to scan on-device. Disabling it removes that code
# path (and its symbol requirements) from the build entirely.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

# Skip building whisper.cpp's own examples/tests/server — we only want
# the library.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# STL must match app/build.gradle.kts (-DANDROID_STL=c++_shared).
# Do NOT set ANDROID_STL here: the NDK toolchain file already consumed the
# Gradle argument, and forcing c++_static while Gradle passes c++_shared
# makes the linker pull libc++_static.a without c++abi / the shared runtime,
# which surfaces as undefined operator new/delete and std::exception.

# Pull in whisper.cpp itself. It ships its own CMakeLists.txt which builds
# a static/shared target literally named "whisper" — that's why our own
# target below is NOT also named "whisper" (CMake target names must be
# unique). We rename our *output file* to whisper instead, further down.
add_subdirectory(whisper_src)

# Our thin JNI bridge that exposes initNativeWhisper / transcribeNative /
# freeNativeWhisper to Kotlin.
add_library(whisper_jni_bridge SHARED
        whisper_jni.cpp
)

# Kotlin calls System.loadLibrary("whisper"), which looks for libwhisper.so.
# This makes our compiled .so file be named that, while keeping the CMake
# target name (whisper_jni_bridge) distinct from whisper.cpp's own "whisper"
# target above.
# LINKER_LANGUAGE CXX: ensure the C++ driver links (pulls in libc++_shared).
set_target_properties(whisper_jni_bridge PROPERTIES
        OUTPUT_NAME "whisper"
        LINKER_LANGUAGE CXX
)

target_include_directories(whisper_jni_bridge PRIVATE
        whisper_src
        whisper_src/include
        whisper_src/ggml/include
)

find_library(log-lib log)
# Nested add_subdirectory(whisper_src) leaves CMAKE_CXX_IMPLICIT_LINK_LIBRARIES
# empty for this target — the link line had no -lc++ at all (see build.ninja
# LINK_LIBRARIES). Must pull the shared STL in explicitly; matches Gradle's
# -DANDROID_STL=c++_shared. Do not use c++_static here (needs c++abi and
# conflicts with the shared STL flag from Gradle).
find_library(cpp-shared c++_shared)

target_link_libraries(whisper_jni_bridge
        -Wl,--start-group
        whisper
        ggml
        ggml-cpu
        ggml-base
        -Wl,--end-group
        ${cpp-shared}
        ${log-lib}
        android
        -lm
        -ldl
        -pthread
        -latomic
)

target_compile_options(whisper_jni_bridge PRIVATE -O3)