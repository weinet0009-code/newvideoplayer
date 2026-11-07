cmake_minimum_required(VERSION 3.22.1)
project(nvpwhisper C CXX)

# whisper.cpp is far too slow without optimisation, so build it optimised even for debug APKs.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
)
FetchContent_MakeAvailable(whisper)

add_library(nvpwhisper SHARED whisper_jni.c)
target_compile_options(nvpwhisper PRIVATE -O3)
target_link_libraries(nvpwhisper whisper log)
