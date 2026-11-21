cmake_minimum_required(VERSION 3.22.1)
project(corehost CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(corehost SHARED
    jni_bridge.cpp
    session.cpp
    libretro_core.cpp
    audio_output.cpp
    video_output.cpp
)
target_compile_options(corehost PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(corehost android log aaudio dl EGL GLESv2)
