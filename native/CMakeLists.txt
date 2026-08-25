cmake_minimum_required(VERSION 3.22.1)
project(toxnode C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(toxnode SHARED
    toxnode.c
    toxnode_jni.c
)

target_include_directories(toxnode PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(toxnode
    PRIVATE
    ${log-lib}
)
