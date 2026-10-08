cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp source: -DLLAMA_CPP_DIR=/path (gradle passes -PllamaCppDir), else android/third_party/llama.cpp,
# else it is downloaded at the pinned commit the app was tested with.
set(LLAMA_CPP_COMMIT d81235049384534c167caea52b85a694f6103d14)
if (NOT LLAMA_CPP_DIR)
    set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
endif()
if (NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    include(FetchContent)
    FetchContent_Declare(llama_cpp GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG ${LLAMA_CPP_COMMIT})
    FetchContent_GetProperties(llama_cpp)
    if (NOT llama_cpp_POPULATED)
        FetchContent_Populate(llama_cpp)
    endif()
    set(LLAMA_CPP_DIR ${llama_cpp_SOURCE_DIR})
endif()
message(STATUS "llama.cpp from ${LLAMA_CPP_DIR}")

# Same recipe as llama.cpp's own Android example: shared libs + one CPU backend per ARM
# feature level, picked at runtime (ggml_backend_load_all_from_path), so it runs fast on
# dotprod/fp16 phones and still runs on older cores.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(jarvis_llm SHARED jarvis_jni.cpp jarvis_llm.cpp)
target_include_directories(jarvis_llm PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/ggml/include)
target_link_libraries(jarvis_llm llama ggml android log)
# 16 KB page alignment (required on newer Android versions)
target_link_options(jarvis_llm PRIVATE "-Wl,-z,max-page-size=16384")
