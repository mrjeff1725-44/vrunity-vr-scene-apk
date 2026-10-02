cmake_minimum_required(VERSION 3.18)
project(vrxr C)

add_library(vrxr SHARED vrxr.c)

# The headset's VR runtime loader, the standard piece every VR app links against.
# The build fetches it before Gradle runs.
add_library(openxr_loader SHARED IMPORTED)
set_target_properties(openxr_loader PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopenxr_loader.so)

target_include_directories(vrxr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/openxr)

find_library(egl_lib EGL)
find_library(gles_lib GLESv2)
find_library(log_lib log)

target_link_libraries(vrxr openxr_loader ${egl_lib} ${gles_lib} ${log_lib})
