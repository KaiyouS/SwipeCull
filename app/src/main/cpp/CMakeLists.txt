cmake_minimum_required(VERSION 3.18)
project(swipecull_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp is cloned here by the CI workflow (see .github/workflows/build-apk.yml).
set(W ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

add_library(swipecull_whisper SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
    ${W}/src/whisper.cpp
    ${W}/ggml/src/ggml.c
    ${W}/ggml/src/ggml-alloc.c
    ${W}/ggml/src/ggml-backend.cpp
    ${W}/ggml/src/ggml-backend-reg.cpp
    ${W}/ggml/src/ggml-quants.c
    ${W}/ggml/src/ggml-threading.cpp
    ${W}/ggml/src/ggml-cpu/ggml-cpu.c
    ${W}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${W}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${W}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${W}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${W}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
    ${W}/ggml/src/ggml-cpu/unary-ops.cpp
    ${W}/ggml/src/ggml-cpu/binary-ops.cpp
)

target_compile_definitions(swipecull_whisper PUBLIC GGML_USE_CPU)

# Always optimise (debug APKs would otherwise be far too slow), and use the ARM
# half-precision + dot-product instructions the Galaxy S25 has.
target_compile_options(swipecull_whisper PRIVATE
    -O3 -DNDEBUG -ffunction-sections -fdata-sections
    -march=armv8.2-a+fp16+dotprod
)
target_link_options(swipecull_whisper PRIVATE -Wl,--gc-sections)

target_include_directories(swipecull_whisper PRIVATE
    ${W} ${W}/src ${W}/include ${W}/ggml/include ${W}/ggml/src ${W}/ggml/src/ggml-cpu
)
find_library(LOG_LIB log)
target_link_libraries(swipecull_whisper ${LOG_LIB} android)
