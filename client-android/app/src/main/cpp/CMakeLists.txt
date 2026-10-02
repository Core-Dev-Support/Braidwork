cmake_minimum_required(VERSION 3.22.1)

project(braidwork_jni LANGUAGES C CXX)

# Ядро собрано Rust-кросс-компиляцией заранее и лежит рядом как готовый .so.
# Собирать его из CMake нельзя: тот же исходник уходит в Windows и Linux
# десктопные сборки, и лишняя сборка внутри Android сломала бы разделение
# сборочных сред (D-001, D-012).

add_library(braidwork_jni SHARED braidwork_jni.c)

target_compile_options(braidwork_jni PRIVATE -Wall -Wextra -fvisibility=hidden)

# Прямая линковка с ядром не нужна и вредна: символы ядра экспортируются
# динамически, а статическая линковка в отдельную .so продублировала бы их.
# Поэтому загрузка идёт по имени через System.loadLibrary.