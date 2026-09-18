# Jarvis TTS native library — ported from parnoldx/omatts (omatts.cpp),
# trimmed to the shared-library FFI target only (no CLI/HTTP executable).

cmake_minimum_required(VERSION 3.28)
project(jarvis_tts VERSION 1.0.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# AGP's CMake integration passes -DCMAKE_BUILD_TYPE=Debug for a Gradle debug
# variant, which silently wins over a plain `if(NOT CMAKE_BUILD_TYPE)` guard
# (the variable IS set, just not to what we want) and compiles all of this
# math-heavy code with zero optimization -- confirmed via compile_commands.json
# showing no -O flag at all on ggml-cpu.c/stt_jni_bridge.cpp. This is almost
# certainly why whisper's encoder pass measured 22-25 SECONDS for a few seconds
# of tiny.en audio (should be well under 1s): FORCE this to Release regardless
# of what the Gradle build variant is. llama.cpp's separate ExternalProject
# build already gets its own explicit -DCMAKE_BUILD_TYPE=Release below and was
# unaffected by this bug, which is exactly why LLM inference was fine while
# whisper/omatts (built directly by this top-level CMake invocation) were not.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# The NDK's default arm64-v8a target is baseline ARMv8.0-A. ggml's ARM fast paths
# (FP16 vector arithmetic, dot-product, matmul-int8) are gated on compile-time
# feature macros, not runtime CPUID -- without explicitly targeting a newer
# architecture level, those macros are unset and everything silently falls back
# to slow scalar code even on hardware that fully supports them (confirmed via
# ggml_cpu_has_fp16_va() returning 0 on a Tensor G3, which does have FP16 SIMD).
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod+fp16")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod+fp16")
endif()

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)

# =============================================================================
# ONNX Runtime
# =============================================================================
set(ONNXRUNTIME_VERSION "1.19.2")

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    # Official prebuilt AAR from Maven Central. It's a zip containing headers
    # and per-ABI .so files; FetchContent pulls and extracts it like any other
    # archive, then we point directly at the paths inside.
    set(ONNXRUNTIME_AAR_URL "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.aar")

    FetchContent_Declare(onnxruntime_aar
        URL ${ONNXRUNTIME_AAR_URL}
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    FetchContent_MakeAvailable(onnxruntime_aar)

    set(ONNXRUNTIME_ROOT ${onnxruntime_aar_SOURCE_DIR})
    set(ONNXRUNTIME_INCLUDE_DIR ${ONNXRUNTIME_ROOT}/headers)
    set(ONNXRUNTIME_LIB ${ONNXRUNTIME_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|amd64|AMD64")
        set(ONNXRUNTIME_ARCH "x64")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
        set(ONNXRUNTIME_ARCH "aarch64")
    endif()
    set(ONNXRUNTIME_URL "https://github.com/microsoft/onnxruntime/releases/download/v${ONNXRUNTIME_VERSION}/onnxruntime-linux-${ONNXRUNTIME_ARCH}-${ONNXRUNTIME_VERSION}.tgz")
    FetchContent_Declare(onnxruntime
        URL ${ONNXRUNTIME_URL}
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    FetchContent_MakeAvailable(onnxruntime)
    set(ONNXRUNTIME_ROOT ${onnxruntime_SOURCE_DIR})
    set(ONNXRUNTIME_INCLUDE_DIR ${ONNXRUNTIME_ROOT}/include)
    set(ONNXRUNTIME_LIB ${ONNXRUNTIME_ROOT}/lib/libonnxruntime.so)
endif()

# =============================================================================
# SentencePiece (static, builds from source — cross-compiles under whatever
# toolchain CMake was invoked with, i.e. the NDK's when driven via
# externalNativeBuild)
# =============================================================================
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)

file(WRITE ${CMAKE_BINARY_DIR}/patch_sentencepiece.cmake "
file(READ \"\${SOURCE_DIR}/src/sentencepiece_processor.h\" CONTENT)
if(NOT CONTENT MATCHES \"#include <cstdint>\")
    string(REPLACE \"#include <string>\" \"#include <string>\\n#include <cstdint>\" CONTENT \"\${CONTENT}\")
    file(WRITE \"\${SOURCE_DIR}/src/sentencepiece_processor.h\" \"\${CONTENT}\")
    message(STATUS \"Patched sentencepiece_processor.h for GCC 14 compatibility\")
endif()
file(READ \"\${SOURCE_DIR}/CMakeLists.txt\" CMLCONTENT)
string(REGEX REPLACE \"cmake_minimum_required\\\\(VERSION [0-9.]+\\\\)\" \"cmake_minimum_required(VERSION 3.14)\" CMLCONTENT \"\${CMLCONTENT}\")
file(WRITE \"\${SOURCE_DIR}/CMakeLists.txt\" \"\${CMLCONTENT}\")
")

FetchContent_Declare(sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG v0.2.1
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${CMAKE_COMMAND} -DSOURCE_DIR=<SOURCE_DIR> -P ${CMAKE_BINARY_DIR}/patch_sentencepiece.cmake
    EXCLUDE_FROM_ALL
)
FetchContent_MakeAvailable(sentencepiece)
target_compile_options(sentencepiece-static PRIVATE -w)

set(SENTENCEPIECE_INCLUDE_DIRS "${sentencepiece_SOURCE_DIR}/src")

# dr_libs (header-only)
FetchContent_Declare(dr_libs
    GIT_REPOSITORY https://github.com/mackron/dr_libs.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(dr_libs)

# =============================================================================
# whisper.cpp (static, builds from source — on-device STT engine)
# =============================================================================
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    EXCLUDE_FROM_ALL
)
FetchContent_MakeAvailable(whispercpp)

set(WHISPER_INCLUDE_DIRS "${whispercpp_SOURCE_DIR}/include" "${whispercpp_SOURCE_DIR}/ggml/include")

# =============================================================================
# llama.cpp (built as an isolated ExternalProject, not FetchContent+
# add_subdirectory — it vendors its own copy of ggml with the same target
# names whisper.cpp's ggml copy uses, so pulling both into one CMake
# configure collides on "ggml"/"ggml-base"/"ggml-cpu". ExternalProject gives
# it its own fully separate CMake configure/build/install, and we link the
# installed static libs as imported targets instead.
# =============================================================================
include(ExternalProject)

set(LLAMA_INSTALL_DIR ${CMAKE_BINARY_DIR}/llama-install)

set(LLAMA_CMAKE_ARGS
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX=${LLAMA_INSTALL_DIR}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DBUILD_SHARED_LIBS=OFF
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=OFF
    -DGGML_OPENMP=OFF
)

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    list(APPEND LLAMA_CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod+fp16
        -DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod+fp16
    )
endif()

ExternalProject_Add(llama_cpp_ext
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b4079
    GIT_SHALLOW TRUE
    GIT_SUBMODULES ""
    CMAKE_ARGS ${LLAMA_CMAKE_ARGS}
    GIT_PROGRESS TRUE
    BUILD_BYPRODUCTS
        ${LLAMA_INSTALL_DIR}/lib/libllama.a
        ${LLAMA_INSTALL_DIR}/lib/libggml.a
    INSTALL_DIR ${LLAMA_INSTALL_DIR}
)

file(MAKE_DIRECTORY ${LLAMA_INSTALL_DIR}/include)

add_library(llama_imported STATIC IMPORTED)
set_target_properties(llama_imported PROPERTIES
    IMPORTED_LOCATION ${LLAMA_INSTALL_DIR}/lib/libllama.a
    INTERFACE_INCLUDE_DIRECTORIES ${LLAMA_INSTALL_DIR}/include
)
add_dependencies(llama_imported llama_cpp_ext)

add_library(ggml_imported STATIC IMPORTED)
set_target_properties(ggml_imported PROPERTIES
    IMPORTED_LOCATION ${LLAMA_INSTALL_DIR}/lib/libggml.a
)
add_dependencies(ggml_imported llama_cpp_ext)

# =============================================================================
# jarvis_llm shared library (LLM FFI target, llama.cpp-backed)
# =============================================================================
add_library(jarvis_llm SHARED
    llm_jni_bridge.cpp
)

target_include_directories(jarvis_llm PRIVATE ${LLAMA_INSTALL_DIR}/include)
add_dependencies(jarvis_llm llama_cpp_ext)

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(jarvis_llm PRIVATE
        llama_imported ggml_imported
        log
    )
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(jarvis_llm PRIVATE
        llama_imported ggml_imported
        pthread dl
    )
endif()

# =============================================================================
# jarvis_stt shared library (STT FFI target, whisper.cpp-backed)
# =============================================================================
add_library(jarvis_stt SHARED
    stt_jni_bridge.cpp
)

target_include_directories(jarvis_stt PRIVATE ${WHISPER_INCLUDE_DIRS})

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(jarvis_stt PRIVATE whisper log)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(jarvis_stt PRIVATE whisper pthread dl)
endif()

# =============================================================================
# jarvis_tts shared library (FFI target, ported from omatts-lib)
# =============================================================================
add_library(jarvis_tts SHARED
    omatts.cpp
    jni_bridge.cpp
)

target_compile_definitions(jarvis_tts PRIVATE PTT_SHARED_LIB)

target_include_directories(jarvis_tts PRIVATE
    ${ONNXRUNTIME_INCLUDE_DIR}
    ${SENTENCEPIECE_INCLUDE_DIRS}
    ${dr_libs_SOURCE_DIR}
)

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    # Bionic: pthread lives in libc (no separate libpthread), libdl/liblog are
    # provided by the NDK sysroot. liblog must come after the static libs that
    # need __android_log_write (abseil, via sentencepiece) for ld.lld to
    # resolve them in a single pass.
    target_link_libraries(jarvis_tts PRIVATE
        ${ONNXRUNTIME_LIB}
        sentencepiece-static
        log
    )
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(jarvis_tts PRIVATE
        ${ONNXRUNTIME_LIB}
        sentencepiece-static
        pthread dl
    )
endif()

# Bundle libonnxruntime.so into the APK's jniLibs output directory so it ships
# alongside libjarvis_tts.so (Gradle packages every .so it finds in the native
# build's output dir).
add_custom_command(TARGET jarvis_tts POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${ONNXRUNTIME_LIB} $<TARGET_FILE_DIR:jarvis_tts>
)
