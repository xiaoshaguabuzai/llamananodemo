cmake_minimum_required(VERSION 3.22.1)
project("llama_wrapper")

# 1. llama.cpp 根目录
set(LLAMA_CPP_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)

# 加入头文件搜索路径
include_directories(
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
)

# 编译选项，和你命令行参数对齐
add_compile_definitions(
        GGML_SHARED
        GGML_USE_CPU
        LLAMA_SHARED
)

add_library(
        llama_wrapper
        SHARED
        llama_wrapper.cpp
)

# 链接 llama.cpp 库
add_subdirectory(${LLAMA_CPP_DIR})
target_link_libraries(llama_wrapper llama log ggml)
