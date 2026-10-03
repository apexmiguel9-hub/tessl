cmake_minimum_required(VERSION 3.22.1)
project(tesslpty C)

add_library(tesslpty SHARED
    pty_jni.c
    ../../../../native/pty/pty_core.c
)

target_include_directories(tesslpty PRIVATE
    ../../../../native/pty
)

target_compile_options(tesslpty PRIVATE
    -Wall -Wextra
    -Werror=implicit-function-declaration
    -fvisibility=hidden
)

# The JNI entry points must stay visible even with -fvisibility=hidden.
target_compile_definitions(tesslpty PRIVATE JNIEXPORT=__attribute__\(\(visibility\("default"\)\)\))

target_link_libraries(tesslpty log)
