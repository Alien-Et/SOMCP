# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copyright (C) 2026 bilieebiliee1-design
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

cmake_minimum_required(VERSION 3.22)
project(blutter_android_runner LANGUAGES C CXX)

if(NOT BLUTTER_SOURCE OR NOT DARTLIB OR NOT RUNNER_LIBRARY)
    message(FATAL_ERROR "BLUTTER_SOURCE, DARTLIB and RUNNER_LIBRARY are required")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(${DARTLIB} REQUIRED)
if(NOT CAPSTONE_ROOT)
    message(FATAL_ERROR "CAPSTONE_ROOT is required")
endif()
add_library(capstone STATIC IMPORTED)
set_target_properties(capstone PROPERTIES
    IMPORTED_LOCATION "${CAPSTONE_ROOT}/lib/libcapstone.a"
    INTERFACE_INCLUDE_DIRECTORIES "${CAPSTONE_ROOT}/include"
)
find_library(LOG_LIBRARY log REQUIRED)
find_library(DL_LIBRARY dl REQUIRED)

set(SRCDIR "${BLUTTER_SOURCE}/src")
include("${BLUTTER_SOURCE}/sourcelist.cmake")
list(FILTER SRCS EXCLUDE REGEX "(^|/)main\\.cpp$")
list(TRANSFORM SRCS PREPEND "${SRCDIR}/")

add_library(${RUNNER_LIBRARY} SHARED ${SRCS} android_runner.cpp)
target_include_directories(${RUNNER_LIBRARY} PRIVATE "${SRCDIR}")
target_include_directories(${RUNNER_LIBRARY} PRIVATE "${CAPSTONE_ROOT}/include/capstone")
target_compile_definitions(${RUNNER_LIBRARY} PRIVATE
    NDEBUG
    RUNNER_ID="${RUNNER_ID}"
    BLUTTER_COMMIT="${BLUTTER_COMMIT}"
    FRIDA_TEMPLATE_DIR="${BLUTTER_SOURCE}/../scripts/"
)
# Dart VM compatibility macros detected by build_runner.py from the installed
# dartvm headers (mirrors upstream blutter.py::find_compat_macro()).  Each one is
# forwarded as -D<name> only when the caller passed -D<name>=ON.
foreach(_blutter_compat_macro IN ITEMS
    OLD_MAP_SET_NAME
    OLD_MAP_NO_IMMUTABLE
    NO_LAST_INTERNAL_ONLY_CID
    HAS_TYPE_REF
    HAS_RECORD_TYPE
    HAS_SHARED_CLASS_TABLE
    NO_INIT_LATE_STATIC_FIELD
    NO_METHOD_EXTRACTOR_STUB
    UNIFORM_INTEGER_ACCESS
)
    if(${_blutter_compat_macro})
        target_compile_definitions(${RUNNER_LIBRARY} PRIVATE ${_blutter_compat_macro})
    endif()
endforeach()
if(NOT COMPRESSED_POINTERS)
    target_compile_definitions(${RUNNER_LIBRARY} PRIVATE NO_CODE_ANALYSIS CSREG_DART_HEAP=ARM64_REG_XZR)
endif()
target_compile_options(${RUNNER_LIBRARY} PRIVATE -O3 -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden -fno-omit-frame-pointer -include ${CMAKE_CURRENT_SOURCE_DIR}/dart_vm_atomic_ref_compat.h)
target_link_libraries(${RUNNER_LIBRARY} PRIVATE ${DARTLIB} capstone ${LOG_LIBRARY} ${DL_LIBRARY})
target_precompile_headers(${RUNNER_LIBRARY} PRIVATE "${SRCDIR}/pch.h")
