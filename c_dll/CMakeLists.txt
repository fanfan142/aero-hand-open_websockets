# Aero Hand WebSocket C DLL
# CMake configuration for building the cross-language WebSocket library
#
# License: Apache-2.0
# Based on: TetherIA/aero-hand-open (https://github.com/TetherIA/aero-hand-open)

cmake_minimum_required(VERSION 3.10)
project(aero_ws_dll C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Output configuration
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

# Source files
set(SOURCES
    src/aero_ws.c
)

# Create shared library
add_library(aero_ws SHARED ${SOURCES})

# Set output properties
set_target_properties(aero_ws PROPERTIES
    OUTPUT_NAME "aero_ws"
    PREFIX ${DLL_PREFIX}
    RUNTIME_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    LIBRARY_OUTPUT_DIRECTORY ${OUTPUT_DIR}
)

# Windows DLL export definition
if(WIN32)
    target_compile_definitions(aero_ws PRIVATE AERO_WS_EXPORTS)
endif()

# Link libraries
if(WIN32)
    target_link_libraries(aero_ws ws2_32)
else()
    target_link_libraries(aero_ws pthread)
endif()

# Install configuration
install(TARGETS aero_ws
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
)

install(FILES include/aero_ws.h
    DESTINATION include
)

include(CTest)

# The socket harness uses Python's standard-library hashlib as an independent
# handshake oracle; no network access or third-party Python packages are needed.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(PythonInterp 3 REQUIRED)

    # Include the implementation to exercise its private SHA-1 routine against
    # the published RFC 6455 vector without exporting test-only DLL symbols.
    add_executable(aero_ws_sha1_test tests/test_sha1.c)
    target_link_libraries(aero_ws_sha1_test PRIVATE pthread)
    add_test(NAME c_dll_sha1_rfc6455 COMMAND aero_ws_sha1_test)

    # All socket scenarios call the public API through the built shared library.
    add_executable(aero_ws_client_test tests/test_client.c)
    target_link_libraries(aero_ws_client_test PRIVATE aero_ws)
    add_test(NAME c_dll_invalid_arguments COMMAND aero_ws_client_test invalid_api)

    foreach(scenario IN ITEMS coalesced fragmented bad_accept bad_status
            missing_accept incomplete_handshake incomplete_frame masked_frame
            orphan_continuation)
        add_test(NAME c_dll_${scenario}
            COMMAND ${PYTHON_EXECUTABLE} -I
                    ${CMAKE_CURRENT_SOURCE_DIR}/tests/socket_server.py
                    $<TARGET_FILE:aero_ws_client_test> ${scenario})
        set_tests_properties(c_dll_${scenario} PROPERTIES TIMEOUT 15)
    endforeach()
endif()
