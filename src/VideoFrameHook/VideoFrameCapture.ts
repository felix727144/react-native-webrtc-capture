/**
 * VideoFrame 捕获模块 TypeScript 包装器
 *
 * @example
 * import { VideoFrameCapture } from './VideoFrameHook/VideoFrameCapture';
 *
 * // 初始化
 * const capture = new VideoFrameCapture();
 *
 * // 开始捕获
 * await capture.startCapture('local-video-track');
 *
 * // 监听帧
 * capture.onFrame((frame) => {
 *   console.log('Frame:', frame.width, frame.height);
 * });
 *
 * // 截图
 * const snapshot = await capture.captureFrame('local-video-track');
 *
 * // 停止捕获
 * await capture.stopCapture('local-video-track');
 */

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

// 使用 WebRTCModule 而不是 VideoFrameCaptureModule
const { WebRTCModule } = NativeModules;
const VideoFrameCaptureModule = WebRTCModule;

// 调试：检查模块是否存在
if (!VideoFrameCaptureModule) {
    console.error('❌ VideoFrameCaptureModule (WebRTCModule) is NULL in NativeModules!');
} else {
    console.log('✅ VideoFrameCaptureModule found via WebRTCModule');
    console.log('Module keys:', Object.keys(VideoFrameCaptureModule));
}

export interface FrameConfig {
  /** 目标帧率 (默认: 30) */
  targetFrameRate?: number;
  /** 数据格式: 'image' | 'pixelBuffer' | 'rawData' | 'base64' (默认: 'image') */
  format?: 'image' | 'pixelBuffer' | 'rawData' | 'base64';
  /** 缩放比例 (默认: 1.0) */
  scale?: number;
  /** 质量 0-100 (默认: 90) */
  quality?: number;
}

export interface VideoFrame {
  /** 轨道 ID */
  trackId: string;
  /** 时间戳 (毫秒) */
  timestamp: number;
  /** 宽度 */
  width: number;
  /** 高度 */
  height: number;
  /** Base64 编码的图像数据 */
  data: string;
  /** 原始 RGBA 数据 (仅 rawData 格式) */
  rawData?: string;
}

export interface SnapshotResult {
  /** 是否成功 */
  success: boolean;
  /** 时间戳 */
  timestamp: number;
  /** 宽度 */
  width: number;
  /** 高度 */
  height: number;
  /** Base64 编码的图像数据 */
  data: string;
  /** 错误信息 */
  error?: string;
}

export type FrameCallback = (frame: VideoFrame) => void;
export type SnapshotCallback = (snapshot: SnapshotResult) => void;
export type ErrorCallback = (error: Error) => void;

class VideoFrameCapture {
  private eventEmitter: NativeEventEmitter;
  private frameListeners: Map<string, FrameCallback> = new Map();
  private snapshotListeners: Map<string, SnapshotCallback> = new Map();
  private errorListeners: ErrorCallback[] = [];
  private isInitialized: boolean = false;

  constructor() {
    this.eventEmitter = new NativeEventEmitter(VideoFrameCaptureModule);
    this.setupListeners();
  }

  /**
   * 设置事件监听
   */
  private setupListeners(): void {
    // 视频帧事件
    this.eventEmitter.addListener('onVideoFrame', (event: VideoFrame) => {
      const callback = this.frameListeners.get(event.trackId);
      if (callback) {
        callback(event);
      }
    });

    // 截图事件
    this.eventEmitter.addListener('onSnapshot', (event: SnapshotResult & { trackId: string }) => {
      const callback = this.snapshotListeners.get(event.trackId);
      if (callback) {
        callback(event);
      }
    });

    // 错误事件
    this.eventEmitter.addListener('onCaptureError', (event: { trackId: string; error: string }) => {
      const error = new Error(event.error);
      this.errorListeners.forEach(listener => listener(error));
    });

    this.isInitialized = true;
  }

  /**
   * 开始捕获指定轨道
   * @param trackId WebRTC 轨道 ID
   * @param config 捕获配置
   */
  async startCapture(trackId: string, config?: FrameConfig): Promise<{ success: boolean; trackId: string }> {
    console.log('📹 [VideoFrameCapture] startCapture called with trackId:', trackId);
    console.log('📹 [VideoFrameCapture] VideoFrameCaptureModule:', VideoFrameCaptureModule);
    
    if (!VideoFrameCaptureModule) {
        console.error('❌ [VideoFrameCapture] Module is NULL!');
        throw new Error('VideoFrameCaptureModule is not available');
    }
    
    try {
      console.log('📹 [VideoFrameCapture] Calling native startCapture...');
      const result = await VideoFrameCaptureModule.startCapture(trackId, config || {});
      console.log('📹 [VideoFrameCapture] Native startCapture returned:', result);
      return result;
    } catch (error) {
      console.error('❌ [VideoFrameCapture] startCapture error:', error);
      throw error;
    }
  }

  /**
   * 停止捕获指定轨道
   * @param trackId WebRTC 轨道 ID
   */
  async stopCapture(trackId: string): Promise<{ success: boolean; trackId: string }> {
    try {
      this.frameListeners.delete(trackId);
      this.snapshotListeners.delete(trackId);
      const result = await VideoFrameCaptureModule.stopCapture(trackId);
      return result;
    } catch (error) {
      console.error('Failed to stop capture:', error);
      throw error;
    }
  }

  /**
   * 停止所有捕获
   */
  async stopAllCaptures(): Promise<{ success: boolean }> {
    try {
      this.frameListeners.clear();
      this.snapshotListeners.clear();
      const result = await VideoFrameCaptureModule.stopAllCaptures();
      return result;
    } catch (error) {
      console.error('Failed to stop all captures:', error);
      throw error;
    }
  }

  /**
   * 截图指定轨道
   * @param trackId WebRTC 轨道 ID
   * @returns 截图结果
   */
  async captureFrame(trackId: string): Promise<SnapshotResult> {
    try {
      const result = await VideoFrameCaptureModule.captureFrame(trackId);
      return result;
    } catch (error) {
      console.error('Failed to capture frame:', error);
      throw error;
    }
  }

  /**
   * 获取帧数据（用于 AI 分析）
   * @param trackId WebRTC 轨道 ID
   * @param format 数据格式: 'rgba' | 'yuv' | 'bitmap'
   */
  async getFrameData(
    trackId: string,
    format: 'rgba' | 'yuv' | 'bitmap' = 'rgba'
  ): Promise<any> {
    try {
      const result = await VideoFrameCaptureModule.getFrameData(trackId, format);
      return result;
    } catch (error) {
      console.error('Failed to get frame data:', error);
      throw error;
    }
  }

  /**
   * 更新捕获配置
   * @param trackId WebRTC 轨道 ID
   * @param config 新配置
   */
  async updateConfig(trackId: string, config: FrameConfig): Promise<{ success: boolean; trackId: string }> {
    try {
      const result = await VideoFrameCaptureModule.updateConfig(trackId, config);
      return result;
    } catch (error) {
      console.error('Failed to update config:', error);
      throw error;
    }
  }

  /**
   * 注册帧回调
   * @param trackId WebRTC 轨道 ID
   * @param callback 回调函数
   */
  onFrame(trackId: string, callback: FrameCallback): () => void {
    this.frameListeners.set(trackId, callback);

    // 返回取消订阅函数
    return () => {
      this.frameListeners.delete(trackId);
    };
  }

  /**
   * 注册截图回调
   * @param trackId WebRTC 轨道 ID
   * @param callback 回调函数
   */
  onSnapshot(trackId: string, callback: SnapshotCallback): () => void {
    this.snapshotListeners.set(trackId, callback);

    // 返回取消订阅函数
    return () => {
      this.snapshotListeners.delete(trackId);
    };
  }

  /**
   * 注册错误回调
   * @param callback 回调函数
   */
  onError(callback: ErrorCallback): () => void {
    this.errorListeners.push(callback);

    // 返回取消订阅函数
    return () => {
      const index = this.errorListeners.indexOf(callback);
      if (index > -1) {
        this.errorListeners.splice(index, 1);
      }
    };
  }

  /**
   * 清理所有资源
   */
  async destroy(): Promise<void> {
    await this.stopAllCaptures();
    this.frameListeners.clear();
    this.snapshotListeners.clear();
    this.errorListeners = [];
  }
}

// 导出单例
export const videoFrameCapture = new VideoFrameCapture();

// 导出类
export default VideoFrameCapture;
