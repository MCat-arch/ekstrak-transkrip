cmake_minimum_required(VERSION 3.22.1)

project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# whisper.cpp / ggml build options
#
# Deliberately conservative:
#  - GGML_NATIVE=OFF       : the build host is x86_64, we cross-compile for arm64
#  - GGML_OPENMP=OFF      : bionic + OpenMP is a source of subtle crashes
#  - no GGML_CPU_ARM_ARCH : keep the NDK default (armv8-a) so Cortex-A53 class
#                           low-end devices don't die on illegal instructions
#  - BUILD_SHARED_LIBS=OFF: one .so with everything statically linked
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS       OFF CACHE BOOL   "" FORCE)
set(WHISPER_BUILD_TESTS     OFF CACHE BOOL   "" FORCE)
set(WHISPER_BUILD_EXAMPLES  OFF CACHE BOOL   "" FORCE)
set(WHISPER_BUILD_SERVER    OFF CACHE BOOL   "" FORCE)
set(WHISPER_CCACHE          OFF CACHE BOOL   "" FORCE)
set(WHISPER_ALL_WARNINGS    OFF CACHE BOOL   "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL   "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL   "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL   "" FORCE)
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL   "" FORCE)
set(GGML_BACKEND_DL         OFF CACHE BOOL   "" FORCE)

add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp resampler.cpp transcribe_job.cpp)

target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden)
# whisper.cpp 1.7.4 exposes no version function in its public header, so the
# version shown in Diagnostics comes from the build definition instead of a
# hardcoded string in C++.
target_compile_definitions(whisper_jni PRIVATE EKSTRAK_WHISPER_VERSION="1.7.4")
target_link_libraries(whisper_jni PRIVATE whisper ggml android log)
