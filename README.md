# PatchNotify

Android 端实时 OCR 监控与阈值提醒。摄像头画面里框选一块区域，循环识别其中的数字/文字，按规则触发提示音或语音播报。

识别**完全在手机本地**完成：C++ 通过 llama.cpp 跑 Qwen3.5-2B（GGUF Q4_0）多模态模型，不联网、不依赖电脑。

## 功能

- CameraX 预览 + 4 fps 抽帧，按区域轮询识别
- 识别方式：本地 Qwen（默认）/ 自动（先数码管）/ 只读数码管 / 只认印刷文字
- 识别内容：数字 / 中文 / 英文 / 混合
- 规则就是一句话：`读数是否已接近 001680`
- 句号后面可以跟一个问题（可选）：`读数是否已接近 001680。还需要充多久？`
  条件成立时用同一张裁剪图再问一次模型，回答内容用语音播报；没有句号就只响提示音
- 判断交给 VLM，只输出「是 / 否」；按「否 → 是」的跃变触发一次，条件恢复后才重新武装
- 区域显示名自动取判断条件的前 10 个字，不再手填字段名
- 每个区域可单独开启「允许模型思考」：开放式判断更准，代价是每次多 ~10 秒
- 帧间指纹缓存：画面几乎没变时直接复用上次读数，不起推理

## 目录

```
app/src/main/java/com/example/patchnotify/
  MainActivity.kt        相机、识别循环、规则触发、TTS
  NativeLlama.kt         JNI 入口 + 模型文件自动发现
  AskRule.kt             一句话规则：拆分条件/播报、拼提示词、解析是/否
  Config.kt              Patch / Rule 数据模型与持久化
app/src/main/cpp/
  pn_llama.cpp           llama.cpp/mtmd 的 JNI 薄封装（图像 → 多模态 prompt → 贪心解码）
scripts/
  build-llama-android.sh 交叉编译 arm64-v8a 的 llama.cpp + JNI 层
```

## 构建

需要：

- Android SDK，NDK **27.2.12479018**
- CMake 3.22.1
- llama.cpp 源码（`~/source/llama.cpp`），需要包含 `tools/mtmd`
- `brew install opencl-headers` **不需要**（纯 CPU 构建用不到）

仓库不包含二进制（`*.so` 与 `app/src/main/jniLibs/` 已在 `.gitignore` 里），自己编一次：

```bash
# 第一次：拉取 pin 住的 llama.cpp（已初始化过就跳过）
git submodule update --init --depth 1
./scripts/build-llama-android.sh
./gradlew assembleDebug
```

`git submodule update` 走 https，若本机连不上 github.com 的 443，可以临时改用 SSH 拉取：

```bash
git -c submodule.third_party/llama.cpp.url=git@github.com:ggml-org/llama.cpp.git \
    submodule update --init --depth 1
```

本机已经有 llama.cpp 源码树时，脚本会自动用它（`LLAMA_DIR=~/source/llama.cpp` 亦可显式指定），不需要再拉一份。

脚本会编出 `libggml-base / libggml-cpu / libggml / libllama / libmtmd / libpnllama.so`
并拷进 `app/src/main/jniLibs/arm64-v8a/`。

### 编译开关（缺一个就慢数倍）

| 开关 | 作用 |
|---|---|
| `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm+bf16` | **最关键**。交叉编译默认按 armv8-a 基线编，量化 GEMV 退回标量路径 |
| `GGML_CPU_KLEIDIAI=ON` | ARM 官方微内核（i8mm/SVE 矩阵乘） |
| `GGML_LTO=ON` | 链接时优化 |
| `GGML_OPENMP=OFF` | Android 上没有可用 OpenMP 运行时 |
| `GGML_VULKAN=OFF` / `GGML_OPENCL=OFF` | 见下方「为什么不走 GPU」 |

校验是否生效：

```bash
llvm-objdump -d app/src/main/jniLibs/arm64-v8a/libggml-cpu.so | grep -cE '\bsdot\b|\bsmmla\b'
# 应为数百条；为 0 说明 ARM 优化没进去
```

## 模型从哪来

权重是 `Qwen/Qwen3.5-2B` 的 GGUF 量化版（原 ONNX 版同样是这个模型的导出）。

```bash
# 模型 ~1.1 GB
curl -L -o Qwen3.5-2B-Q4_0.gguf \
  https://hf-mirror.com/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_0.gguf
# 视觉编码器 ~637 MB
curl -L -o mmproj-F16.gguf \
  https://hf-mirror.com/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf
```

放到手机任意目录（例如 `/storage/emulated/0/patchnotify/qwen_2b/`），然后在
「设条件 → 选择文件夹」里指过去。

模型文件名按优先级自动发现，想测别的量化就把那个文件改名成 **`model.gguf`** 丢进同一目录：

```
model.gguf                        ← 最高优先级
Qwen3.5-2B-Q4_0.gguf
Qwen3.5-2B-UD-Q3_K_XL.gguf
Qwen3.5-2B-UD-Q4_K_XL.gguf
Qwen3.5-2B-UD-Q2_K_XL.gguf
Qwen-3.5-0.8B-OCR.*.gguf          ← 0.8B 精度不够，会把首位 0 读成 9
```

mmproj 侧会找 `mmproj-F16.gguf` / `mmproj-BF16.gguf` / `mmproj.gguf`。

### 量化选择

2bit（`IQ2_M` / `Q2_K_XL`）在这类数码管上会稳定读错末两位，**Q3_K_XL 是精度下限**；
`Q4_0` 与 `Q3_K_XL` 准确率相当，但 Q4_0 有手写 ARM 重排内核，CPU 上更快，因此作为默认。

## 性能

天玑 9300（Cortex-X4×4 + A720×4）实测，495×173 的框：

| 后端 | 图像块 | 文本块 | decode 6 token | 合计 |
|---|---|---|---|---|
| Vulkan | 3.18 s | 1.60 s | 1.45 s | 6.2 s |
| **CPU + KleidiAI** | 3.9 s | 0.23 s | 0.29 s | **4.5 s** |

CPU 版本还带来两个副作用：不占 GPU，相机预览不卡；没有 Vulkan 的每次计算图重建开销。

### 线程数

默认取 `min(4, 核心数)`——只有 4 个大核，线程数超过大核数会被小核拖住
（实测 8 线程比 4 线程慢 2.5 倍）。

想调就在模型目录放一个 `threads.txt`，写一个数字（1–8），重启监控生效。

### 为什么不走 GPU

- **Vulkan**：能跑，但 Mali/Immortalis 上每个 `llama_decode` 的算子派发开销极高，
  逐 token 解码比 CPU 慢 5 倍；每帧还有约 1.6 s 的计算图重建
- **OpenCL**：`libOpenCL.so` 在 `/vendor/lib64/` 下，普通 App 的 linker namespace 访问不到，
  除非 root 或改 SELinux，否则用不了
- **Hexagon NPU**：需要高通 Hexagon SDK 编 DSP 侧 skel 库并经 adb 部署，装不进普通 APK

## 权限

- 相机
- **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`）：模型放在用户文件管理器可见的目录，系统只允许用户手动在设置里开启，App 无法弹窗申请

也可以在「设条件 → 导入模型到 App 内」通过系统的文件夹选择器把模型复制进应用私有目录，这样不需要该权限。

## 说明

- 输入尺寸不写死：按框选区域实际大小交给 mtmd 处理，`image_max_tokens` 上限 128
  （与旧 ONNX 版 smart_resize 产出的 token 数对齐；压到 64 会把首位 0 读成 9）
- 默认关掉 Qwen3.5 的 thinking：在 assistant 开头预填空 think 块，输出从数百 token 降到几个；
  每个区域可以单独打开（`Patch.think`），此时预算给 800 token，生成后按 `</think>` 截断；
  若到上限还没闭合，判定为思考被截断，自动改用不思考重跑一次
- 为什么不让模型做数值判断：实测 2B 对「读数是否超过 5000」这类封闭问句有明显 yes-bias
  （001679 也答"是"），所以数值类判断直接用 thinking + 让模型自己推理，或把条件写成它能答对的封闭问句
- 每个区域按分钟存一张裁剪图：`logs/<条件前10字>_<id>/20261115_053012.jpg`（JPEG q70，
  通常 10~30 KB）。条件触发的那一帧额外存一张带 `_hit` 后缀的，不受 60 秒限制。
  每区域保留最近 240 张（4 小时），目录总量封顶 256 MB，并清理 3 天前的旧图
- 运行日志写在模型目录的父目录：`pnocr.log`（Kotlin 侧）和 `pnocr_cpp.log`（C++ 侧）
- 区域参数保存在 SharedPreferences / 应用私有 JSON
