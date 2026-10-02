cmake_minimum_required(VERSION 3.22.1)

project("terbaca_pdfium" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt PDFium library from bblanchon/pdfium-binaries (chromium/8076)
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

# Terbaca PDFium JNI bridge library
add_library(terbaca_pdfium SHARED
    pdfium_jni.cpp
)

target_compile_options(terbaca_pdfium PRIVATE
    -fno-exceptions
    -fno-rtti
)

target_include_directories(terbaca_pdfium PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(terbaca_pdfium
    pdfium
    ${log-lib}
    ${android-lib}
)

