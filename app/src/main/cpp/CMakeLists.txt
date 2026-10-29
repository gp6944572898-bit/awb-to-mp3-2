cmake_minimum_required(VERSION 3.18)
project(awbconv C)

# Исходники сторонних библиотек скачиваются скриптом scripts/fetch-native.sh
set(VGM_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/vgmstream)
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)

if(NOT EXISTS ${VGM_DIR}/src/libvgmstream.h)
    message(FATAL_ERROR "Нет исходников vgmstream: запустите scripts/fetch-native.sh")
endif()
if(NOT EXISTS ${LAME_DIR}/include/lame.h)
    message(FATAL_ERROR "Нет исходников LAME: запустите scripts/fetch-native.sh")
endif()

# ---- vgmstream (статическая библиотека; HCA/AWB встроены, внешние кодеки отключены) ----
set(VGM_SRC ${VGM_DIR}/src)
file(GLOB VGM_SOURCES
    ${VGM_SRC}/base/*.c
    ${VGM_SRC}/coding/*.c
    ${VGM_SRC}/coding/libs/*.c
    ${VGM_SRC}/layout/*.c
    ${VGM_SRC}/meta/*.c
    ${VGM_SRC}/util/*.c
    ${VGM_SRC}/*.c)

add_library(vgmstream STATIC ${VGM_SOURCES})
target_include_directories(vgmstream PUBLIC ${VGM_SRC})
target_include_directories(vgmstream PRIVATE
    ${VGM_SRC}/base ${VGM_SRC}/coding ${VGM_SRC}/layout ${VGM_SRC}/meta ${VGM_SRC}/util
    ${VGM_DIR}/ext_includes)
target_compile_definitions(vgmstream PRIVATE VGM_LOG_OUTPUT)
target_compile_options(vgmstream PRIVATE -w -O2 -fPIC)

# ---- LAME (только кодировщик, без mpglib) ----
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)
list(FILTER LAME_SOURCES EXCLUDE REGEX "mpglib_interface\\.c$")

add_library(mp3lame STATIC ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC ${LAME_DIR}/include)
target_include_directories(mp3lame PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lame_config ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE -w -O2 -fPIC)

# ---- JNI-библиотека ----
add_library(awbconv SHARED converter.c jni_bridge.c)
target_link_libraries(awbconv vgmstream mp3lame m log)
# выравнивание страниц 16 КБ (требование новых версий Android)
target_link_options(awbconv PRIVATE -Wl,-z,max-page-size=16384)
