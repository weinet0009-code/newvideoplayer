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

# LAME 3.100 (LGPL, vendored in lame/) for real MP3 output.
file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)
add_library(nvplame SHARED lame_jni.c ${LAME_SOURCES})
target_include_directories(nvplame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(nvplame PRIVATE HAVE_CONFIG_H)
target_compile_options(nvplame PRIVATE -O2 -w)
target_link_libraries(nvplame m)

# Own FFmpeg decoders (LGPL FFmpeg + dav1d, built by ffmpeg/build.sh into app/ffmpeg/<abi>).
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../ffmpeg/${ANDROID_ABI})
if (EXISTS ${FFMPEG_DIR}/lib/libavcodec.a)
    add_library(nvpffmpeg SHARED ffmpeg_audio_jni.cc ffmpeg_video_jni.cc)
    target_include_directories(nvpffmpeg PRIVATE ${FFMPEG_DIR}/include)
    target_compile_options(nvpffmpeg PRIVATE -O2)
    target_link_options(nvpffmpeg PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL)
    target_link_libraries(nvpffmpeg
        ${FFMPEG_DIR}/lib/libavcodec.a
        ${FFMPEG_DIR}/lib/libswscale.a
        ${FFMPEG_DIR}/lib/libswresample.a
        ${FFMPEG_DIR}/lib/libavutil.a
        ${FFMPEG_DIR}/lib/libdav1d.a
        android log m z)
else()
    message(WARNING "FFmpeg not found in ${FFMPEG_DIR}; run ffmpeg/build.sh. Building without FFmpeg decoders.")
endif()
