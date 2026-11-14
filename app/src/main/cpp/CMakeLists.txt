cmake_minimum_required(VERSION 3.22.1)
project(pnocr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ORT_INC ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/include/onnxruntime)
set(ORT_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${ORT_LIB})

add_library(pnocr SHARED pn_ocr.cpp)
target_include_directories(pnocr PRIVATE ${ORT_INC})
target_link_libraries(pnocr onnxruntime log android)

target_compile_options(pnocr PRIVATE -O3 -ffast-math -fno-exceptions -fno-rtti)
