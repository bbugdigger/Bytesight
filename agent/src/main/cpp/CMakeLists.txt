cmake_minimum_required(VERSION 3.20)
project(bytesight_heap LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is not set — cannot locate jni.h / jvmti.h")
endif()

set(JDK_HOME $ENV{JAVA_HOME})
message(STATUS "Using JDK at: ${JDK_HOME}")

add_library(bytesight_heap SHARED
    src/agent_entry.cpp
    src/capabilities.cpp
    src/snapshot.cpp
    src/object_reader.cpp
    src/search.cpp
    src/jni_bridge.cpp
)

target_include_directories(bytesight_heap PRIVATE
    ${JDK_HOME}/include
    ${JDK_HOME}/include/win32
    include
)

# Hide internal symbols, export only JNI entry points
if(MSVC)
    target_compile_options(bytesight_heap PRIVATE /W3 /EHsc)
    target_compile_definitions(bytesight_heap PRIVATE _CRT_SECURE_NO_WARNINGS)
endif()

set_target_properties(bytesight_heap PROPERTIES
    OUTPUT_NAME "bytesight_heap"
    PREFIX ""
)
