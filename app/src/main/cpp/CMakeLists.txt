cmake_minimum_required(VERSION 3.22.1)

project(aether_pty)

add_library(aetherpty SHARED aether_pty.cpp)

find_library(log-lib log)

target_link_libraries(aetherpty ${log-lib})
