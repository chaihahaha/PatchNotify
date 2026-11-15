#!/usr/bin/env bash
# 生成「免配置安装包」用的模型资产（体积大，不入库）。
#
#   ./scripts/make-model-assets.sh [模型 gguf] [mmproj gguf]
#
# 产物：
#   app/src/main/assets/model/Qwen3.5-2B-Q4_0.gguf       直接放原始 gguf
#   app/src/main/assets/mmproj/mmproj-Q8_0.gguf          mmproj 从 F16 量化到 Q8_0
#
# 不放 .tenz：IQ2 张量 tenz 压缩不了（实测只省 4.6%），首启还要多写一遍，
# 不如直接拷 GGUF —— 反正 llama.cpp 只认文件路径，这一份拷贝省不掉。
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=${1:-$HOME/hermes-share/ggufs2b/Qwen3.5-2B-Q4_0.gguf}
MMPROJ=${2:-$HOME/hermes-share/ggufs2b/mmproj-F16.gguf}
BUILD=.tenz-build
mkdir -p "$BUILD" app/src/main/assets/model app/src/main/assets/mmproj

QUANT=$(ls "$HOME"/source/llama.cpp/build/bin/llama-quantize 2>/dev/null || true)
[ -n "$QUANT" ] || { echo "缺少 llama-quantize，先 cmake --build build --target llama-quantize"; exit 1; }

cp "$MODEL" app/src/main/assets/model/$(basename "$MODEL")
echo "== mmproj F16 -> Q8_0"
"$QUANT" "$MMPROJ" app/src/main/assets/mmproj/mmproj-Q8_0.gguf Q8_0 "$(sysctl -n hw.ncpu)"

ls -lh app/src/main/assets/model app/src/main/assets/mmproj
echo
echo "注意：ModelBundle.kt 里写死了解压后的字节数，换模型要同步改 ITEMS。"
