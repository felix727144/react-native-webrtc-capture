# react-native-webrtc (增强版 - 支持截图)

> 📸 基于官方 react-native-webrtc 增强版本，新增 **VideoFrame 截图功能**（仅支持 Android）

[![npm](https://img.shields.io/npm/v/react-native-webrtc.svg)](https://www.npmjs.com/package/react-native-webrtc)
[![npm](https://img.shields.io/npm/dm/react-native-webrtc.svg)](https://www.npmjs.com/package/react-native-webrtc)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://reactnative.dev/)

---

## 📖 项目简介

本项目源自 [react-native-webrtc](https://github.com/react-native-webrtc/react-native-webrtc)，由于原项目没有提供截图功能，我们使用 **Minimax** 和 **Qwen** AI 助手修改了源码，增加了实时视频帧截图功能。

**当前状态：**
- ✅ Android 截图功能已实现
- ❌ iOS 截图功能（待实现）

---

## 🚀 快速开始

### 1. 安装依赖

```bash
cd examples/GumTestApp
npm install
```

### 2. 启动开发服务器

```bash
# 启动 Metro Bundler
npm start

# 或者在新终端中
npx react-native start
```

### 3. 运行应用

```bash
# Android
npm run android

# 或者
npx react-native run-android
```

---

## 📸 截图功能使用指南

### API 说明

#### `VideoFrameCaptureModule.captureFrame(trackId)`

捕获指定视频轨道的当前帧。

**参数：**
- `trackId` (string): 视频轨道的 ID

**返回值：**
- `Promise<{ success: boolean, data?: string, error?: string }>`
  - `success`: 是否成功
  - `data`: Base64 编码的 JPEG 图片（成功时）
  - `error`: 错误信息（失败时）

### 使用示例

```javascript
import { mediaDevices, VideoFrameCaptureModule } from 'react-native-webrtc';

async function takeSnapshot() {
  try {
    // 获取本地视频流
    const stream = await mediaDevices.getUserMedia({
      video: {
        facingMode: 'user',
        width: { ideal: 1280 },
        height: { ideal: 720 }
      }
    });

    // 获取视频轨道 ID
    const videoTrack = stream.getVideoTracks()[0];
    const trackId = videoTrack.id;

    console.log('Track ID:', trackId);

    // 调用截图功能
    const result = await VideoFrameCaptureModule.captureFrame(trackId);

    if (result.success) {
      console.log('截图成功！');
      // result.data 是 Base64 格式的 JPEG 图片
      const imageUri = `data:image/jpeg;base64,${result.data}`;
      
      // 可以用于显示、保存或上传
      // 例如：<Image source={{ uri: imageUri }} />
    } else {
      console.error('截图失败:', result.error);
    }
  } catch (error) {
    console.error('错误:', error);
  }
}
```

### 完整示例代码

```javascript
import React, { useState, useRef } from 'react';
import { View, Button, Image, StyleSheet } from 'react-native';
import {
  mediaDevices,
  RTCView,
  VideoFrameCaptureModule
} from 'react-native-webrtc';

export default function App() {
  const [stream, setStream] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const streamRef = useRef(null);

  const startCamera = async () => {
    try {
      const newStream = await mediaDevices.getUserMedia({
        video: {
          facingMode: 'user',
          width: { ideal: 1280 },
          height: { ideal: 720 }
        }
      });
      setStream(newStream);
      streamRef.current = newStream;
    } catch (error) {
      console.error('启动摄像头失败:', error);
    }
  };

  const takeSnapshot = async () => {
    if (!streamRef.current) {
      alert('请先启动摄像头');
      return;
    }

    try {
      const videoTrack = streamRef.current.getVideoTracks()[0];
      const result = await VideoFrameCaptureModule.captureFrame(videoTrack.id);

      if (result.success) {
        setSnapshot(`data:image/jpeg;base64,${result.data}`);
        console.log('截图成功！尺寸:', result.width, 'x', result.height);
      } else {
        alert('截图失败：' + result.error);
      }
    } catch (error) {
      console.error('截图错误:', error);
      alert('截图异常：' + error.message);
    }
  };

  const stopCamera = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => track.stop());
      streamRef.current = null;
      setStream(null);
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.videoContainer}>
        {stream && <RTCView streamURL={stream.toURL()} style={styles.video} />}
      </View>

      {snapshot && (
        <View style={styles.snapshotContainer}>
          <Image source={{ uri: snapshot }} style={styles.snapshot} />
        </View>
      )}

      <View style={styles.buttonRow}>
        <Button title="启动摄像头" onPress={startCamera} />
        <Button title="截图" onPress={takeSnapshot} />
        <Button title="停止" onPress={stopCamera} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  videoContainer: {
    flex: 1,
  },
  video: {
    flex: 1,
  },
  snapshotContainer: {
    height: 200,
    margin: 10,
    backgroundColor: '#333',
  },
  snapshot: {
    flex: 1,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    padding: 10,
  },
});
```

---

## 🔧 技术实现细节

### 核心修改

截图功能通过以下核心组件实现：

1. **VideoFrameAdapter** - 拦截 WebRTC 视频帧
2. **VideoFrameCapturer** - 将 YUV 帧转换为 Bitmap
3. **VideoFrameCaptureModule** - React Native 原生模块接口

### 视频处理流程

```
摄像头 → NV21/NV12 → MediaCodec → TextureBuffer (GPU)
                                      ↓
                              VideoFrameAdapter (拦截)
                                      ↓
                              toI420() 转换
                                      ↓
                              YUV → JPEG 压缩
                                      ↓
                              Base64 编码 → JavaScript
```

### 关键文件

```
android/src/main/java/com/oney/WebRTCModule/
├── VideoFrameHook/
│   ├── VideoFrameAdapter.java      # 视频帧拦截器
│   └── VideoFrameCapturer.java     # 帧捕获和转换
├── VideoFrameCaptureModule.java    # React Native 模块
└── WebRTCModule.java               # 主模块（包含截图逻辑）
```

---

## 📋 API 参考

### `mediaDevices.getUserMedia(constraints)`

获取本地媒体流。

```javascript
const stream = await mediaDevices.getUserMedia({
  video: {
    facingMode: 'user',  // 'user' | 'environment'
    width: { ideal: 1280 },
    height: { ideal: 720 },
    frameRate: { ideal: 30 }
  },
  audio: true
});
```

### `VideoFrameCaptureModule.captureFrame(trackId)`

捕获视频帧。

```javascript
const result = await VideoFrameCaptureModule.captureFrame(trackId);
// result: { success: true, data: "base64...", width: 1280, height: 720 }
```

---

## 🐛 已知问题

1. **仅支持 Android** - iOS 版本尚未实现
2. **性能影响** - 截图时会轻微影响视频渲染性能
3. **内存占用** - 高分辨率截图会暂时增加内存使用

---

## 🛠️ 开发调试

### 查看日志

```bash
# Android Logcat
adb logcat | grep -E "VideoFrame|WebRTC"

# 过滤截图相关日志
adb logcat | grep "VideoFrameCapturer"
```

### 关键日志输出

```
===== convertToBitmap: Buffer class = org.webrtc.TextureBufferImpl
===== convertToBitmap: frame size = 1280x720
===== convertToBitmap: I420 size = 1280x720
===== YUV stats (fixed): Y avg=128, U avg=180, V avg=150
===== JPEG size: 145663
===== Bitmap created: 1280x720
```

---

## 📚 参考资料

- [官方 react-native-webrtc](https://github.com/react-native-webrtc/react-native-webrtc)
- [WebRTC 官方文档](https://webrtc.org/)
- [React Native 文档](https://reactnative.dev/)

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 待实现功能

- [ ] iOS 截图支持
- [ ] 视频录制功能
- [ ] 实时滤镜处理
- [ ] 自定义分辨率/质量设置

---

## 📄 许可证

本项目遵循与原始项目相同的许可证（MIT License）。

---

## 🙏 致谢

- 感谢 [react-native-webrtc](https://github.com/react-native-webrtc/react-native-webrtc) 团队的基础工作
- 感谢 **Minimax** 和 **Qwen** AI 助手在开发过程中的技术支持

---

**最后更新：** 2026 年 4 月 1 日
