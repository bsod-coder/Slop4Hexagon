cmake_minimum_required(VERSION 3.22.1)
project(slop4hexagon_jni)

add_library(slop4hexagon_jni SHARED
    QnnHtpAdapter.cpp
)

find_library(log-lib log)

# QNN available only if SDK present: user sets -DQNN_SDK_ROOT and -DQNN_AVAILABLE=ON
option(QNN_AVAILABLE "QNN SDK available" OFF)
if(QNN_AVAILABLE)
    message(STATUS "QNN SDK enabled: ${QNN_SDK_ROOT}")
    target_include_directories(slop4hexagon_jni PRIVATE ${QNN_SDK_ROOT}/include/QNN)
    # Proprietary libs are not committed; user must provide path
    # target_link_libraries(slop4hexagon_jni ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtp.so)
    target_compile_definitions(slop4hexagon_jni PRIVATE QNN_AVAILABLE=1)
endif()

target_link_libraries(slop4hexagon_jni ${log-lib})
