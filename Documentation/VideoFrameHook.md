---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 3046022100b14de0cd7b13a70da6a791fc1a9f33f7babe8d7ed4b8ca4913370a2830b5b96c022100b5f65f06f64f7fc414bfc9bc13eb832227bf2db4a021452d9d8887bcee772742
    ReservedCode2: 3045022056b97d42ea5a52fdbd08813420c841dc254804066d0605db96a47de1966950440221008961ae5fade94211024c9c692c51ab17bd7a01d7aaf30cf21ca7b283124c6edd
---

# VideoFrame Hook - WebRTC 视频帧捕获与处理

## 概述

VideoFrame Hook 是对 react-native-webrtc 的扩展，实现了 Hook WebRTC 视频帧的功能，可以将 VideoFrame 转换为 Bitmap/RawBuffer，用于 AI 分析、实时截图和视频编码处理。

## 功能特性

- **帧捕获**: 实时捕获 WebRTC 视频帧
- **格式转换**: 支持 YUV/I420 → Bitmap/JPEG/PNG/RGBA 转换
- **实时截图**: 任意时刻获取当前帧截图
- **AI 分析**: 提供原始帧数据用于机器学习分析
- **视频编码**: 支持获取原始视频数据用于编码
- **跨平台**: 同时支持 Android (Java) 和 iOS (Objective-C)

## 安装

### Android

无需额外配置，VideoFrameCaptureModule 已在 `WebRTCModulePackage` 中自动注册。

### iOS

无需额外配置，新文件已包含在 podspec 中。运行 `pod install` 即可。

## 使用方法

### 基础使用

```typescript
import {
  VideoFrameCapture,
  videoFrameCapture,
  useVideoFrameCapture
} from 'react-native-webrtc';

// 方式 1: 使用类
const capture = new VideoFrameCapture();

// 开始捕获
await capture.startCapture('track-id', {
  targetFrameRate: 30,
  format: 'image'
});

// 监听帧
capture.onFrame('track-id', (frame) => {
  console.log('Frame:', frame.width, frame.height);
  // frame.data 是 base64 编码的 JPEG 图像
});

// 截图
const snapshot = await capture.captureFrame('track-id');
console.log('Snapshot:', snapshot.width, snapshot.height);

// 停止捕获
await capture.stopCapture('track-id');
```

### 使用 React Hook

```typescript
import { useVideoFrameCapture } from 'react-native-webrtc';

function VideoComponent({ trackId }) {
  const {
    isCapturing,
    frames,
    lastSnapshot,
    startCapture,
    stopCapture,
    captureSnapshot
  } = useVideoFrameCapture(trackId, {
    config: { targetFrameRate: 30 },
    onFrame: (frame) => {
      // 处理每一帧
    },
    onSnapshot: (snapshot) => {
      // 处理截图
    }
  });

  useEffect(() => {
    startCapture();
    return () => stopCapture();
  }, []);

  return (
    <View>
      <Text>Capturing: {isCapturing ? 'Yes' : 'No'}</Text>
      <Button title="Snapshot" onPress={captureSnapshot} />
      {lastSnapshot && (
        <Image
          source={{ uri: `data:image/jpeg;base64,${lastSnapshot.data}` }}
        />
      )}
    </View>
  );
}
```

### AI 分析集成

```typescript
import { useAIScreenAnalysis } from 'react-native-webrtc';

// 假设你有一个 AI 模型
const aiModel = {
  async analyzeImage(imageData) {
    // 实现 AI 分析逻辑
    // 例如：物体检测、人脸识别、图像分类等
    return { result: 'analyzed', confidence: 0.95 };
  }
};

function AIAnalysisComponent({ trackId }) {
  const {
    isAnalyzing,
    analysisResults,
    startAnalysis,
    stopAnalysis
  } = useAIScreenAnalysis({
    trackId,
    model: aiModel,
    interval: 500, // 分析间隔（毫秒）
    onResult: (result) => {
      console.log('AI Result:', result);
    }
  });

  return (
    <View>
      <Button
        title={isAnalyzing ? 'Stop' : 'Start AI Analysis'}
        onPress={isAnalyzing ? stopAnalysis : startAnalysis}
      />
      {analysisResults.map((result, i) => (
        <Text key={i}>{JSON.stringify(result)}</Text>
      ))}
    </View>
  );
}
```

## API 参考

### VideoFrameCapture 类

#### 方法

| 方法 | 参数 | 返回值 | 描述 |
|------|------|--------|------|
| `startCapture` | `trackId: string, config?: FrameConfig` | `Promise<{success, trackId}>` | 开始捕获指定轨道 |
| `stopCapture` | `trackId: string` | `Promise<{success, trackId}>` | 停止捕获 |
| `stopAllCaptures` | - | `Promise<{success}>` | 停止所有捕获 |
| `captureFrame` | `trackId: string` | `Promise<SnapshotResult>` | 截图 |
| `getFrameData` | `trackId: string, format: 'rgba' \| 'yuv' \| 'bitmap'` | `Promise<any>` | 获取帧数据用于 AI |
| `updateConfig` | `trackId: string, config: FrameConfig` | `Promise<{success, trackId}>` | 更新配置 |

#### 配置 (FrameConfig)

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `targetFrameRate` | `number` | `30` | 目标帧率 |
| `format` | `'image' \| 'pixelBuffer' \| 'rawData' \| 'base64'` | `'image'` | 数据格式 |
| `scale` | `number` | `1.0` | 缩放比例 |
| `quality` | `number` | `90` | JPEG 质量 (0-100) |

#### 事件

| 事件名 | 回调参数 | 描述 |
|--------|----------|------|
| `onVideoFrame` | `(frame: VideoFrame) => void` | 视频帧回调 |
| `onSnapshot` | `(snapshot: SnapshotResult) => void` | 截图回调 |
| `onCaptureError` | `(error: Error) => void` | 错误回调 |

### 类型定义

```typescript
interface VideoFrame {
  trackId: string;      // 轨道 ID
  timestamp: number;    // 时间戳 (毫秒)
  width: number;        // 宽度
  height: number;       // 高度
  data: string;         // Base64 编码的图像数据
  rawData?: string;     // 原始数据 (仅 rawData 格式)
}

interface SnapshotResult {
  success: boolean;     // 是否成功
  timestamp: number;    // 时间戳
  width: number;        // 宽度
  height: number;       // 高度
  data: string;         // Base64 编码的图像数据
  error?: string;       // 错误信息
}
```

## 原生实现细节

### Android (Java)

关键类：
- `VideoFrameCapturer`: 将 `VideoFrame` 转换为 `Bitmap`
- `VideoFrameAdapter`: Hook WebRTC 的 `VideoSink`
- `VideoFrameCaptureModule`: React Native 桥接模块

代码位置：
```
android/src/main/java/com/oney/WebRTCModule/VideoFrameHook/
├── VideoFrameCapturer.java       # 帧捕获器
├── VideoFrameAdapter.java        # 帧适配器
├── VideoFrameCaptureModule.java # RN 模块
└── VideoFrameCapturePackage.java # 包注册
```

### iOS (Objective-C)

关键类：
- `VideoFrameCapturer`: 将 `RTCVideoFrame` 转换为 `UIImage/CVPixelBuffer`
- `VideoFrameAdapter`: Hook WebRTC 的帧输出
- `VideoFrameCaptureModule`: React Native 桥接模块

代码位置：
```
ios/RCTWebRTC/VideoFrameHook/
├── VideoFrameCapturer.h/m      # 帧捕获器
├── VideoFrameAdapter.h/m       # 帧适配器
└── VideoFrameCaptureModule.h/m # RN 模块
```

## 性能优化建议

1. **降低帧率**: 不需要 30fps 时，使用较低帧率节省资源
2. **选择合适格式**: AI 分析推荐使用 `rawData` 格式，避免 JPEG 编码开销
3. **及时释放内存**: 处理完帧后及时 `recycle()` Bitmap
4. **异步处理**: 所有帧处理操作都在子线程执行
5. **帧缓存**: 仅保留需要的帧，避免内存泄漏

## 常见问题

### Q: 如何获取 trackId？

```typescript
// 通过 RTCPeerConnection 获取轨道
peerConnection.getTransceivers().forEach(transceiver => {
  const track = transceiver.receiver.track;
  console.log('Track ID:', track.id);
});
```

### Q: 如何处理帧率过高导致的性能问题？

```typescript
await capture.startCapture(trackId, {
  targetFrameRate: 15, // 降低到 15fps
  format: 'rawData'    // 使用原始数据避免编码
});
```

### Q: 如何保存截图到相册？

```typescript
import { CameraRoll } from '@react-native-camera-roll/camera-roll';

const snapshot = await capture.captureFrame(trackId);
const base64Data = snapshot.data;

// 保存到相册
await CameraRoll.save(`data:image/jpeg;base64,${base64Data}`, {
  type: 'photo'
});
```

## License

MIT License - 与 react-native-webrtc 相同
