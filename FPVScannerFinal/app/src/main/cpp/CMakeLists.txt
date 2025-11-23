cmake_minimum_required(VERSION 3.18)
project(hackrfbackend)

set(CMAKE_CXX_STANDARD 17)

# Наш native-бекенд
add_library(
        hackrfbackend
        SHARED
        hackrf_backend.cpp
)

# Шлях до hackrf.h
target_include_directories(
        hackrfbackend
        PRIVATE
        ${CMAKE_SOURCE_DIR}/include
)

# Підключаємо вже зібрані .so з jniLibs/arm64-v8a
add_library(hackrf SHARED IMPORTED)
set_target_properties(hackrf PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libhackrf.so)

add_library(usb1_0 SHARED IMPORTED)
set_target_properties(usb1_0 PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libusb-1.0.so)

find_library(
        log-lib
        log
)

target_link_libraries(
        hackrfbackend
        hackrf
        usb1_0
        ${log-lib}
)
