cmake_minimum_required(VERSION 3.21)

project(voyu_desktop LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.7 REQUIRED COMPONENTS Core Gui Network Widgets)

qt_standard_project_setup(REQUIRES 6.7)

qt_add_executable(voyu_desktop
    src/main.cpp
    src/mainwindow.cpp
    src/mainwindow.h
    src/mainwindow.ui
    src/voyuclient.cpp
    src/voyuclient.h
)

target_link_libraries(voyu_desktop
    PRIVATE
        Qt6::Core
        Qt6::Gui
        Qt6::Network
        Qt6::Widgets
)

set_target_properties(voyu_desktop PROPERTIES
    WIN32_EXECUTABLE TRUE
    MACOSX_BUNDLE TRUE
)
