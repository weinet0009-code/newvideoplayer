cmake_minimum_required(VERSION 3.22.1)
project(nvpwhisper C CXX)

# whisper.cpp is far too slow without optimisation, so build it optimised even for debug APKs.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Build every Android CPU variant of ggml (plain ARMv8.0, ARMv8.2 dotprod/fp16, ARMv8.6 i8mm,
    # ARMv9 SVE2/SME) as separate libggml-cpu-*.so files. At runtime ggml scores each one against
    # the phone's CPU and loads the fastest one it supports.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
)
FetchContent_MakeAvailable(whisper)

add_library(nvpwhisper SHARED whisper_jni.c)
target_compile_options(nvpwhisper PRIVATE -O3)
target_link_libraries(nvpwhisper whisper ggml log)
