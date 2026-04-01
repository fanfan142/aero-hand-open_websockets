# Aero Hand WebSocket C DLL CMake配置

cmake_minimum_required(VERSION 3.10)
project(aero_ws_dll C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 输出配置
set(BUILD_SHARED_LIBS ON)

if(WIN32)
    set(DLL_PREFIX "")
    set(DLL_EXTENSION ".dll")
    set(OUTPUT_DIR ${CMAKE_BINARY_DIR}/bin)
else()
    set(DLL_PREFIX "lib")
    set(DLL_EXTENSION ".so")
    set(OUTPUT_DIR ${CMAKE_BINARY_DIR}/lib)
endif()

include_directories(include)

# 源文件
set(SOURCES
    src/aero_ws.c
)

# 创建动态库
add_library(aero_ws SHARED ${SOURCES})

# 设置输出名称
set_target_properties(aero_ws PROPERTIES
    OUTPUT_NAME "aero_ws"
    PREFIX ${DLL_PREFIX}
    RUNTIME_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    LIBRARY_OUTPUT_DIRECTORY ${OUTPUT_DIR}
)

# Windows DLL导出定义
if(WIN32)
    target_compile_definitions(aero_ws PRIVATE AERO_WS_EXPORTS)
endif()

# 链接库
if(WIN32)
    target_link_libraries(aero_ws ws2_32)
else()
    target_link_libraries(aero_ws pthread)
endif()

# 安装配置
install(TARGETS aero_ws
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
)

install(FILES include/aero_ws.h
    DESTINATION include
)
