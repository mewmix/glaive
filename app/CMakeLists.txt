cmake_minimum_required(VERSION 3.22.1)

project("glaive")

add_library(
        glaive_core
        SHARED
        src/main/cpp/glaive_core.c)

# Target high performance
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Ofast -march=armv8-a+crypto -flto")
target_compile_options(glaive_core PRIVATE
    -Wall
    -std=c99
    -fno-exceptions
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
)

find_library(log-lib log)

target_link_libraries(
        glaive_core
        ${log-lib})
