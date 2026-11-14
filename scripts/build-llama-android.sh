#!/usr/bin/env bash
# 编译 llama.cpp 的 Android(arm64-v8a) 纯 CPU 后端 + PatchNotify 的 JNI 封装层。
#
# 产物（全部拷进 app/src/main/jniLibs/arm64-v8a/，该目录被 .gitignore 忽略）：
#   libggml-base.so libggml-cpu.so libggml.so libllama.so libmtmd.so libpnllama.so
# libc++_shared.so 需要单独从 NDK sysroot 拷一次，见文末。
#
# 性能相关的编译开关（缺一个都会慢数倍）：
#   GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm+bf16
#       交叉编译时 llama.cpp 默认按 armv8-a 基线编，量化 GEMV 会退回标量路径。
#       天玑 9300（Cortex-X4/A720）实测开启后 decode 从 ~380ms/token 降到 ~48ms/token。
#   GGML_CPU_KLEIDIAI=ON   ARM 官方微内核（i8mm/SVE 矩阵乘）
#   GGML_LTO=ON            链接时优化
#   GGML_OPENMP=OFF        Android 上没有可用的 OpenMP 运行时
#
# llama.cpp 以 git submodule 形式放在 third_party/llama.cpp，克隆后执行：
#   git submodule update --init --depth 1
#
# 用法（默认就用仓库内的 submodule）：
#   ./scripts/build-llama-android.sh
# 也可以指到外部的 llama.cpp 源码树：
#   LLAMA_DIR=~/source/llama.cpp ./scripts/build-llama-android.sh
set -euo pipefail

ANDROID_NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.2.12479018}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# 优先用仓库内的 submodule；没初始化就退回本机已有的 llama.cpp 源码树
if [ -z "${LLAMA_DIR:-}" ]; then
  for c in "$PROJECT_DIR/third_party/llama.cpp" "$HOME/source/llama.cpp"; do
    if [ -f "$c/CMakeLists.txt" ]; then LLAMA_DIR="$c"; break; fi
  done
fi
if [ -z "${LLAMA_DIR:-}" ] || [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
  echo "找不到 llama.cpp 源码。执行 git submodule update --init --depth 1 或设置 LLAMA_DIR" >&2
  exit 1
fi
if [ -z "${BUILD_DIR:-}" ]; then
  BUILD_DIR="$PROJECT_DIR/.llama-build-cpu"
  # 复用外部源码树里已有的构建目录，避免重复全量编译
  if [ ! -f "$BUILD_DIR/CMakeCache.txt" ] && [ -f "$LLAMA_DIR/build-android-cpu/CMakeCache.txt" ]; then
    BUILD_DIR="$LLAMA_DIR/build-android-cpu"
  fi
fi
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
ABI=arm64-v8a
API=28

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"

echo "==> configure ($BUILD_DIR)"
cmake -S "$LLAMA_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+fp16+i8mm+bf16" \
  -DGGML_CPU_KLEIDIAI=ON \
  -DGGML_LTO=ON \
  -DGGML_VULKAN=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_SERVER=OFF

echo "==> build llama + mtmd"
cmake --build "$BUILD_DIR" -j "$(sysctl -n hw.ncpu 2>/dev/null || nproc)" --target llama mtmd

echo "==> build JNI wrapper"
mkdir -p "$JNILIBS"
"$TOOLCHAIN/bin/aarch64-linux-android$API-clang++" -shared -fPIC -O2 -std=c++17 \
  -I"$LLAMA_DIR/include" -I"$LLAMA_DIR/ggml/include" -I"$LLAMA_DIR/tools/mtmd" \
  "$PROJECT_DIR/app/src/main/cpp/pn_llama.cpp" \
  -L"$BUILD_DIR/bin" -lllama -lmtmd -lggml -lggml-base -llog \
  -Wl,-rpath,'$ORIGIN' -o "$JNILIBS/libpnllama.so"

echo "==> copy + strip"
for f in libggml-base.so libggml-cpu.so libggml.so libllama.so libmtmd.so; do
  cp "$BUILD_DIR/bin/$f" "$JNILIBS/$f"
done
# libc++_shared.so 只需拷一次（NDK 版本变化时重拷）
if [ ! -f "$JNILIBS/libc++_shared.so" ]; then
  cp "$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNILIBS/"
fi
for f in "$JNILIBS"/*.so; do
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$f" 2>/dev/null || true
done

echo "==> done"
ls -lh "$JNILIBS"
echo
echo "校验（应能看到 sdot/smmla 指令，否则说明 ARM 优化没生效）："
echo "  $TOOLCHAIN/bin/llvm-objdump -d $JNILIBS/libggml-cpu.so | grep -cE '\\bsdot\\b|\\bsmmla\\b'"
