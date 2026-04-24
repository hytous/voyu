QT += core gui widgets network

CONFIG += c++17

SOURCES += \
    src/main.cpp \
    src/mainwindow.cpp \
    src/voyuclient.cpp

HEADERS += \
    src/mainwindow.h \
    src/voyuclient.h

FORMS += \
    src/mainwindow.ui

TARGET = voyu_desktop

win32:CONFIG += windows
