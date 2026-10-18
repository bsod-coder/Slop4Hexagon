cmake_minimum_required(VERSION 3.22.1)
project(slop_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# The portable CPU engine. Deliberately no third-party dependencies and no
# link-time dependency on any Qualcomm library: the QNN boundary uses dlopen
# only, so this target builds without the proprietary SDK present.
# ---------------------------------------------------------------------------
add_library(slop_native SHARED
        slop_engine.cpp
        native_jni.cpp
        qnn_probe.cpp)

# -ffp-contract=off keeps the deterministic checksum reduction independent of
# FMA contraction, so results are comparable across compilers and runs.
target_compile_options(slop_native PRIVATE
        -Wall
        -Wextra
        -Werror
        -ffp-contract=off
        -O2)

if(ANDROID)
    target_link_libraries(slop_native PRIVATE log)
endif()

# ---------------------------------------------------------------------------
# Host verification target: `cmake -DSLOP_HOST_TESTS=ON ..` then `make
# slop_native_tests`. Used by tools/offline-native-check on a dev machine or in
# a CI sandbox without the Android NDK. Never part of the APK.
# ---------------------------------------------------------------------------
option(SLOP_HOST_TESTS "Build the host-side native kernel test binary" OFF)
if(SLOP_HOST_TESTS)
    add_executable(slop_native_tests
            native_tests.cpp
            slop_engine.cpp)
    target_compile_options(slop_native_tests PRIVATE
            -Wall
            -Wextra
            -ffp-contract=off
            -O2)
    enable_testing()
    add_test(NAME slop_native_tests COMMAND slop_native_tests)
endif()
