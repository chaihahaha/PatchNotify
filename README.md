# PatchNotify

Android 端本地 VLM 画面监控。摄像头画面里框选一块区域，每 0.25 秒取一帧交给手机上的 Qwen3.5-2B 判断一个自然语言条件，成立时响铃或语音播报。

识别**完全在手机本地**完成：C++ 通过 llama.cpp 跑 Qwen3.5-2B（GGUF Q4_0）多模态模型，不联网、不依赖电脑。

## 功能

- CameraX 预览 + 4 fps 抽帧，按区域轮询识别
- 识别方式：本地 Qwen 多模态（Q4_0 GGUF + mmproj），完全离线
- 识别内容：数字 / 中文 / 英文 / 混合（由自然语言条件决定看什么）
- 每个区域可以有多行触发条件，**任意一行**满足就执行那一行自己的动作（多行是「或」关系）
- 每行是两列独立输入：左列「条件」（模型只答是/否），右列「问题」（命中后回答并播报，可留空）
- 工厂高对比界面：白底黑字、黑框按钮；详细说明收进「?」按钮，列名只用「条件 / 问题」
- 条件成立时用同一张裁剪图再问一次模型，回答内容用语音播报；问题留空则只响提示音
- 判断交给 VLM，只输出「是 / 否」；按「否 → 是」的跃变触发一次，条件恢复后才重新武装
- 区域显示名自动取第一条判断条件的前 10 个字，不再手填字段名
- 旧版的「条件。问题」单行配置和按行分隔的条件，升级后会自动迁移成多行两列
- 长按下方条件标题可选中方框：拖框内移动、拖四角缩放；点框外退出
- 顶部「导入」随时可用；「导出」只在长按选中方框后出现，导出该方框（坐标 + 全部条件/问题/思考/脚本路径）
- 每一行可单独开启「允许模型思考」：开放式判断更准，代价是每次多 ~10 秒
- 帧间指纹缓存：画面几乎没变时直接复用上次读数，不起推理
- 语音播报前先响一声 2.8 kHz 方波（只响一次），再念 AI 回答
- 主按钮行右侧的**手电筒图标**：灰色无光线=关，黄色带光线=开；点一下让闪光灯常亮补光
- 每一行触发条件可以单独配一个 sdcard 上的 Python 脚本：这一行由否变是时，
  在语音播报之外异步执行脚本，脚本能拿到该行的判定条件和模型回答
- 监控期间窗口保持常亮；没有前台服务，App 切到后台时相机会被系统解绑、监控暂停

## 目录

```
app/src/main/java/com/example/patchnotify/
  MainActivity.kt        相机、判断循环、条件触发、TTS、手电筒
  NativeLlama.kt         JNI 入口 + 模型文件自动发现
  Condition.kt           判断提示词、旧配置的「。」迁移、解析是/否
  Config.kt              Patch / TriggerRule 多行规则数据模型与持久化
  PythonRunner.kt        Chaquopy 解释器 + 用户脚本异步执行
  PatchOverlayView.kt    预览叠加层（框选/命中状态）
  PatchShot.kt           裁剪图/整帧/事件日志存档
app/src/main/python/
  runner.py              用户脚本入口：注入变量、捕获 stdout/stderr、执行 runpy
app/src/main/cpp/
  pn_llama.cpp           llama.cpp/mtmd 的 JNI 薄封装（图像 → 多模态 prompt → 贪心解码）
scripts/
  build-llama-android.sh 交叉编译 arm64-v8a 的 llama.cpp + JNI 层
```

## 构建

需要：

- Android SDK，NDK **27.2.12479018**
- CMake 3.22.1
- JDK 17+（AGP 8.5 / Gradle 8.9）
- llama.cpp 源码（`~/source/llama.cpp`），需要包含 `tools/mtmd`
- 首次联网构建会下载 Chaquopy 16.1 + CPython 3.12 运行库（之后缓存在 `~/.gradle`）
- 打包 1.5 GB 的 GGUF 资产需要较大的 Gradle 堆；`gradle.properties` 已设 `-Xmx6g`
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

权重是 `Qwen/Qwen3.5-2B` 的 GGUF 量化版。

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

## 模型实测（2026-11-17，桌面 CPU，4 线程）

测试目的：确认 GBNF 之后 0.8B 能否替代 2B，以及 LiquidAI d1-2B 这类
system-one decision 模型是否适合做触发判断。测试在 Mac 上用 llama.cpp
纯 CPU（`-ngl 0 --no-mmproj-offload -t 4`）跑，**手机速度只能看相对比例**。

测试图：
- 用户提供真实照片（读数 **001679**）裁出的 tight 400×210 / wide 600×420，
  以及 blur / noise / low_contrast / occlusion / glare 变体
- 合成七段数码管 480×160：24 个读数 × 6 种变体（clean / gaussian_blur /
  noise / low_contrast / occlusion / glare）
- Qwen 使用与 App 完全相同的 ChatML prompt、空 think 预填和
  `root ::= "是" | "否"` GBNF；d1 走 `/v1/systemone` 的 `noul` / `choice`

“平衡准确率”= (正例召回 + 负例召回) / 2；原始准确率会被“总是答否”抬高。
0.8B 全量合成集里 **总是答否的基线约 76%**，所以不能只看 81.2%。

| 模型 | 样本 | 原始准确率 | 平衡准确率 | 正例召回 | 中位延迟 |
|---|---|---|---|---|---|
| Qwen3.5-2B Q4_0（基线） | 139 合成 | 82.7% | 77.7% | 56.6% | 1106 ms |
| Qwen3.5-0.8B Q4_0 | 576 合成 | 81.2% | 60.9% | 21.7% | 405 ms |
| d1-2B Q4_0（noul） | 120 合成 | 80.0% | 66.7% | 33.3% | 942 ms |
| d1-2B Q4_0（choice 选读数） | 30 合成 | **93.3%** | — | — | 1067 ms |

分条件正例召回（合成集）：

| 条件 | 2B | 0.8B | d1 noul |
|---|---:|---:|---:|
| `≥ 001680` | 66.7% | 5.0% | 33.3% |
| `< 001000` | 0% | 0% | 0% |
| `接近 001680`（±10） | 33.3% | 16.7% | 16.7% |
| `含数字 8` | 100% | 100% | 100% |

同 query 对比：
- 2B vs 0.8B（同样 139 条）：2B 原始/平衡 **82.7/77.7**，0.8B **74.8/67.0**；
  2B 高 7.9pp / 10.7pp，但慢 2.5×（1106 vs 445 ms）
- d1 vs 0.8B（同样 120 条）：两者都是 **80.0/66.7**；d1 慢约 2.1×
- 2B vs d1（91 条交集）：2B **82.4/77.7**，d1 **74.7/67.1**；
  d1 低 7.7pp，但延迟是 2B 的 0.87×（快约 13%）

真实 001679：
- 0.8B：4 个 official 条件共 48 条，**64.6%**，平衡 43.1%，正例召回 0；
  `等于 001679` 的 12 张 sanity 全错；`含 8` 因退化/背景误报 7/12；
  它会把读数描述成 `00 / 16 / 79` 三组
- 2B：tight/clean 的 official **3/4**，`等于 001679` **2/2**（样本很小）
- d1：tight+wide clean 的 official **6/8**，`等于 001679` **1/2**；
  `choice` 选读数 tight 0.60 / wide 0.69，均选中 001679
- d1 choice 分变体：clean/blur/noise/low_contrast 100%，occlusion/glare 80%

结论：
- **0.8B 不推荐替换 2B**：快约 2.5~2.7 倍，但平衡准确率只有 60.9%，
  数值比较的正例召回 0~5%，基本在“答否”；GBNF 只约束输出格式，
  不会提升读数/推理能力
- **d1-2B 不适合直接替换**：它不是聊天模型，`noul` 阈值判断偏“否”，
  且需要新增 systemone 协议；但 **choice 选读数 93.3%**，说明
  “d1 选读数 + 本地数值比较”可能是后续更准/更快的路线
- **2B 仍是默认**；`接近` 这类模糊条件所有模型都弱，建议写成明确阈值
  （`是否大于等于 001680`）或精确值
- 2B/d1 是时间截断后的部分样本，且合成图与真实照片有分布差；
  真实 001679 对 2B/d1 只有 1~2 张图，不能当完整真实集结论

## 方框编辑

长按下方条件标题：

- 对应方框会显示四个白底黑框的角点
- 在框内拖动 = 移动
- 拖四个角 = 缩放
- 点框外 = 退出编辑
- 松开手指后才写配置并清掉旧裁剪图的推理缓存

## JSON 导入 / 导出

顶部有「导入」按钮；长按条件标题选中方框后，右侧才会出现「导出」按钮：

- 「导出」只导出当前选中的方框，不会一次导出所有方框
- 点框外退出选中后，导出按钮会自动隐藏，提示文字也会恢复
- 导出的 JSON 包含方框归一化坐标、`monitor`、全部 `rules`
  （`condition` / `question` / `think` / `py_path` / `references`）
- `references` 里是参考图路径 + 名称 + 说明，不嵌入图片文件
- 导入后会在当前列表里新增方框和条件，并自动选中新方框；点「设条件」即可继续改
- Python 脚本只导出**路径**，不导出脚本内容；换设备后路径可能需要重新选
- JSON 使用系统文件选择器保存/读取，不需要手动找路径

## 参考图

每条条件行都可以单独启用参考图：

- 先勾选「参考图」，才会出现「添加」按钮；最多 4 张
- 每张图可以写名称/代号和说明
- 提示词里会把参考图写成 `参考图1（名称）：说明`，**最后一张固定叫「实时监控画面」**（手机摄像头当前帧）
- 思考模式只描述最后的实时监控画面，参考图只用于对比，不会让模型把参考图当成当前画面
- 选图使用系统文件选择器，只授予这张图的读取权限；不支持持久授权时会复制到 App 私有目录
- 导出 JSON 会包含参考图的路径/名称/说明，但不嵌入图片文件；换设备后路径可能需要重新选

## 手电筒

主按钮行右侧的手电筒图标直接调 CameraX 的 `CameraControl.enableTorch`，不额外申请权限：

- 灰色无光线 = 关闭；黄色带光线 = 常亮；再点一次切换
- 有闪光灯才启用；无闪光灯时图标置灰
- App 退到后台或销毁时自动关掉（相机已经被系统解绑）
- 补光只影响画面，不改变判断逻辑；耗电和发热会明显增加

## Python 脚本（条件命中后执行）

App 内置 Chaquopy 的 CPython 3.12，不需要安装 Termux，也不需要联网。每条触发条件行
先勾选「Python 动作」，才会显示脚本路径输入框；填路径（通常是 `/sdcard/patchnotify/xxx.py`），
或点「选择」从文件管理器挑（外部存储的会转成真实路径；其它 provider 会复制到 App 私有目录）。

触发时机：这一行判定从「否」变成「是」且通过 8 秒重新武装/冷却检查时，与语音播报
**并行**执行；条件一直为「是」不会重复执行。同一区域多行同时命中时，语音会排队依次播报，
Python 脚本排队串行执行（最多排 8 个）。脚本在独立线程跑，不阻塞相机、推理和 TTS；
队列满或脚本失败只写日志/Toast，不影响监控。

脚本里可用这些全局变量：

| 变量 | 含义 |
|---|---|
| `PATCH_ID` / `PATCH_TITLE` | 区域 id / 显示名（条件前 10 字） |
| `PATCH_CONDITION` | 这一行的判断条件 |
| `PATCH_QUESTION` | 这一行命中后要模型回答的问题，可能为空 |
| `PATCH_ANSWER` | 模型这次回答（没有追问时为空串） |
| `APP_FILES_DIR` | App 私有目录，适合写持久文件 |
| `SCRIPT_PATH` | 当前脚本绝对路径 |

脚本可选设置 `RESULT`，它会和 stdout/stderr 一起写进 App 日志
（`sdcard/patchnotify/app.log`，或私有目录）。示例：

```python
# /sdcard/patchnotify/on_hit.py
import json, os, time

record = {
    "at": time.strftime("%Y-%m-%d %H:%M:%S"),
    "title": PATCH_TITLE,
    "condition": PATCH_CONDITION,
    "answer": PATCH_ANSWER,
}
path = os.path.join(os.environ.get("HOME", APP_FILES_DIR), "hits.jsonl")
with open(path, "a", encoding="utf-8") as f:
    f.write(json.dumps(record, ensure_ascii=False) + "\n")
RESULT = record
```

注意：

- 解释器是进程级单例，第一次执行前会预热 1~2 秒；只配了脚本的区域才会触发预热
- 内置的是 CPython 标准库，不含 numpy / requests 等第三方包；需要时可在
  `chaquopy { defaultConfig { pip { install(...) } } }` 里加，APK 会相应变大
- 脚本没有沙箱：能读写有权访问的目录。不要在里面写死循环，否则会一直占住 Python 线程，
  后续脚本只能排队（队列满后跳过，App 不会因此崩溃）
- 没授予「所有文件访问权限」时，sdcard 路径可能读不到；可以用「选择」让 App 复制到私有目录
- 脚本失败只会在日志和 Toast 里显示，不影响原本的响铃/播报/监控

## 权限

- 相机
- **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`）：模型、截图和 Python 脚本放在用户文件管理器可见的目录，系统只允许用户手动在设置里开启，App 无法弹窗申请

不想授权也可以把模型放到 App 私有目录（adb push 到 `/data/data/com.example.patchnotify/files/models/`，
或使用带模型资产的安装包，首次启动会自动释放），推理和判断不依赖这个权限。

## 说明

- 输入尺寸不写死：按框选区域实际大小交给 mtmd 处理，`image_max_tokens = 0`
  （不额外压缩；压到 64 会把首位 0 读成 9）
- 默认关掉 Qwen3.5 的 thinking：在 assistant 开头预填空 think 块，输出从数百 token 降到几个；
  每个区域可以单独打开（`Patch.think`），此时预算给 2048 token，生成后按 `</think>` 截断；
  若到上限还没闭合，判定为思考被截断，自动改用不思考重跑一次
- 为什么不让模型做数值判断：实测 2B 对「读数是否超过 5000」这类封闭问句有明显 yes-bias
  （001679 也答"是"），所以数值类判断直接用 thinking + 让模型自己推理，或把条件写成它能答对的封闭问句
- 每个区域按分钟存一张裁剪图：`logs/<第一条条件前10字>_<id>/20261115_053012.jpg`（JPEG q70，
  通常 10~30 KB）。任意一行条件触发的那一帧额外存一张带 `_hit` 后缀的，不受 60 秒限制。
  同目录下每次改配置都会**新写一份** `meta_20261115_140300.json`（从不覆盖旧文件），
  记录 set_at（用户保存条件的时间）、归一化矩形、像素矩形、全部触发规则、思考开关、帧尺寸、设备与版本；
  `events.log` 记录每次命中的时间/判定/问题/回答。
- 每天另存一张**未裁剪的整帧**到 `logs/_daily/20261115_0600.jpg`（q80，保留 180 天），
  用来回溯这些裁剪图当时拍的是什么
- 每区域截图各自封顶 2 GB（不设张数上限），超了按时间从旧到新删；无全局上限
- 运行日志写在模型目录的父目录：`app.log`（Kotlin 侧）和 `native.log`（C++ 侧）；
  两者都按 2 MB 轮转，长期运行不会把存储写满
- 区域参数保存在 SharedPreferences / 应用私有 JSON

## 许可证

本项目以 GNU General Public License v3.0 发布，全文见 [LICENSE](LICENSE)。

## 免配置安装包（模型打进 APK）

源码默认 APK 不含权重（没执行 `make-model-assets.sh` 时），用户把模型放到
`/sdcard/patchnotify/qwen_2b/`（或在模型目录里放 `threads.txt` 调线程数）即可。
想做成装完就能用的版本，把 GGUF 放进 assets：

```
app/src/main/assets/model/Qwen3.5-2B-Q4_0.gguf         1159 MiB
app/src/main/assets/mmproj/mmproj-Q8_0.gguf            345 MiB（从 F16 量化，637 → 345）
```

首次启动时 `ModelBundle` 把资产释放到**应用私有目录** `files/models/`，校验字节数后结束
（私有目录不需要存储权限，也不会被清理软件删掉）：

- **用户自己放的模型优先**：`<sdcard>/patchnotify/qwen_2b/` 里有模型 + mmproj 就完全不动
- 目标文件已存在且大小正确 → 跳过，重装/升级不会重复拷 1.2 GB
- 普通构建的 assets 里没有这些文件，`bundled()` 返回 false，整段逻辑不执行
- 生成资产：`./scripts/make-model-assets.sh`
- **文件访问权限是可选的**：没授权时只读私有目录里的模型，不扫 sdcard，不写截图，
  `app.log` 落私有目录；推理和判断完全不受影响
- APK 约 1.5 GB；安装要先复制进 `/data/app`，首次启动再释放一份到私有目录

为什么用 Q4_0 而不是更小的 IQ2_M：repack / KleidiAI 只覆盖 Q4_0、Q4_K、Q2_K、Q5_K、Q6_K、Q8_0，
IQ2_M 走通用 iq2 内核。同一份编译下实测 decode 14.65 vs 29.40 t/s（2 倍差）。
也不压成 .tenz：llama.cpp 只接受文件路径、没法直接 mmap APK 里的 asset，拷贝本来就要做一次。
