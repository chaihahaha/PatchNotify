#!/usr/bin/env bash
# 生成「免配置安装包」用的模型资产（体积大，不入库）。
#
#   ./scripts/make-model-assets.sh [模型 gguf] [mmproj gguf]
#
# 产物：
#   app/src/main/assets/model/model.gguf.tenz    tenz 压过的模型
#   app/src/main/assets/mmproj/mmproj.gguf.tenz  mmproj 先量化 Q8_0 再 tenz
#
# 需要：curl、cmake 编出来的 llama-quantize、tenz（自动下载到 .tenz-build/）
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=${1:-$HOME/hermes-share/ggufs2b/Qwen3.5-2B-Q4_0.gguf}
MMPROJ=${2:-$HOME/hermes-share/ggufs2b/mmproj-F16.gguf}
BUILD=.tenz-build
mkdir -p "$BUILD" app/src/main/assets/model app/src/main/assets/mmproj

if [ ! -x "$BUILD/tenz" ]; then
  echo "== 拉取 tenz 源码并编译"
  curl -fsSL https://raw.githubusercontent.com/nsiddiqui248/tenz/main/tenz.c -o "$BUILD/tenz.c"
  cc -O2 -pthread -ffp-contract=off -o "$BUILD/tenz" "$BUILD/tenz.c" -lm
fi

QUANT=$(ls "$HOME"/source/llama.cpp/build/bin/llama-quantize 2>/dev/null || true)
[ -n "$QUANT" ] || { echo "缺少 llama-quantize，先 cmake --build build --target llama-quantize"; exit 1; }

echo "== mmproj F16 -> Q8_0"
"$QUANT" "$MMPROJ" "$BUILD/mmproj-Q8_0.gguf" Q8_0 "$(sysctl -n hw.ncpu)"

echo "== tenz 压缩"
"$BUILD/tenz" c "$MODEL"  app/src/main/assets/model/model.gguf.tenz  -f
"$BUILD/tenz" c "$BUILD/mmproj-Q8_0.gguf" app/src/main/assets/mmproj/mmproj.gguf.tenz -f

cat <<TXT

== 完成
模型     $(du -h "$MODEL" | cut -f1) -> $(du -h app/src/main/assets/model/model.gguf.tenz | cut -f1)
mmproj   $(du -h "$MMPROJ" | cut -f1) -> $(du -h "$BUILD/mmproj-Q8_0.gguf" | cut -f1) -> $(du -h app/src/main/assets/mmproj/mmproj.gguf.tenz | cut -f1)

注意：ModelBundle.kt 里写死了解压后的字节数，换模型要同步改 ITEMS。
TXT
