# PatchNotify

Android 端实时 OCR 监控与阈值提醒。摄像头画面里框选一块区域，循环识别其中的数字/文字，按规则触发提示音或语音播报。

识别**完全在手机本地**完成：C++ 通过 ONNX Runtime 直接跑 Qwen2-VL 2B（q4），不联网、不依赖电脑。

## 功能

- CameraX 预览 + 4 fps 抽帧，按区域轮询识别
- 识别方式：本地 Qwen（默认）/ 自动（先数码管）/ 只读数码管 / 只认印刷文字
- 识别内容：数字 / 中文 / 英文 / 混合
- 多条规则独立触发：每越过一个阈值提醒一次
- 首次读数只建立基线，开机时已经超标的阈值不会立刻报警
- 播报内容可省略，只播提示音；播报时长可设
- 阈值、播报、秒数的文本格式：`字段>191 好了 5`（兼容旧写法 `字段>191=>好了=>5`）

## 目录

```
app/src/main/java/com/example/patchnotify/
  MainActivity.kt        相机、识别循环、规则触发、TTS
  ConditionParser.kt     条件 DSL 与规则文本解析
  SegmentReader.kt       七段数码管解码
  Config.kt              Patch / Rule 数据模型与持久化
  NativeOcr.kt           JNI 入口
app/src/main/cpp/
  pn_ocr.cpp             本地推理全流程（smart_resize → patchify → vision → embed → 自回归解码）
  prompt_tokens.h        四种识别类型的固定 prompt token id
  CMakeLists.txt
```

## 构建

需要：

- Android SDK，NDK **27.2.12479018**
- CMake 3.22.1
- ONNX Runtime Android **1.32.0**（q4 权重依赖 `GatherBlockQuantized.bits`，1.22 会报 `Unrecognized attribute`）

仓库不包含二进制，需要自己放三处：

1. **ORT 头文件** → `app/src/main/cpp/thirdparty/include/onnxruntime/`
2. **ORT 动态库** → `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so`
3. **模型**（三组 `.onnx` + 同名 `.onnx_data`）放到手机 `/storage/emulated/0/patchnotify/qwen_2b/onnx/`
   - `vision_encoder_fp16.onnx`
   - `embed_tokens_q4.onnx`
   - `decoder_model_merged_q4.onnx`

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 权限

- 相机
- **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`）：模型放在用户文件管理器可见的目录，系统只允许用户手动在设置里开启，App 无法弹窗申请

也可以在「设条件 → 导入模型到 App 内」通过系统的文件夹选择器把模型复制进应用私有目录，这样不需要该权限。

## 说明

- 输入尺寸不写死：按框选区域实际大小做 32 对齐的 smart_resize，图像 token 超过上限时直接报错提示用户缩小框，不静默缩图
- NNAPI 只挂 vision encoder（decoder 每步 KV cache shape 变化，NNAPI 无法编译）；运行期失败会自动退回 CPU
- 阈值、秒数等参数保存在 SharedPreferences / 应用私有 JSON
