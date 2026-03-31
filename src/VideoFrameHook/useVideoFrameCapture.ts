/**
 * React Hook: useVideoFrameCapture
 *
 * 用于在 React Native 组件中方便地使用 VideoFrame 捕获功能
 *
 * @example
 * import { useVideoFrameCapture } from './useVideoFrameCapture';
 *
 * function VideoComponent({ trackId }) {
 *   const { frames, captureSnapshot, isCapturing, startCapture, stopCapture } = useVideoFrameCapture(trackId);
 *
 *   useEffect(() => {
 *     startCapture();
 *     return () => stopCapture();
 *   }, []);
 *
 *   return (
 *     <View>
 *       <Text>Frames captured: {frames.length}</Text>
 *       <Button title="Snapshot" onPress={captureSnapshot} />
 *     </View>
 *   );
 * }
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { VideoFrameCapture, VideoFrame, SnapshotResult, FrameConfig } from './VideoFrameCapture';

interface UseVideoFrameCaptureOptions {
  /** 自动开始捕获 */
  autoStart?: boolean;
  /** 捕获配置 */
  config?: FrameConfig;
  /** 帧回调 */
  onFrame?: (frame: VideoFrame) => void;
  /** 截图回调 */
  onSnapshot?: (snapshot: SnapshotResult) => void;
  /** 错误回调 */
  onError?: (error: Error) => void;
}

interface UseVideoFrameCaptureReturn {
  /** 是否正在捕获 */
  isCapturing: boolean;
  /** 最近捕获的帧列表 */
  frames: VideoFrame[];
  /** 最近截图结果 */
  lastSnapshot: SnapshotResult | null;
  /** 开始捕获 */
  startCapture: () => Promise<void>;
  /** 停止捕获 */
  stopCapture: () => Promise<void>;
  /** 截图 */
  captureSnapshot: () => Promise<SnapshotResult>;
  /** 清除帧列表 */
  clearFrames: () => void;
  /** 更新配置 */
  updateConfig: (config: FrameConfig) => Promise<void>;
  /** 捕获模块实例 */
  capture: VideoFrameCapture;
}

export function useVideoFrameCapture(
  trackId: string,
  options: UseVideoFrameCaptureOptions = {}
): UseVideoFrameCaptureReturn {
  const {
    autoStart = false,
    config = {},
    onFrame,
    onSnapshot,
    onError
  } = options;

  const [isCapturing, setIsCapturing] = useState(false);
  const [frames, setFrames] = useState<VideoFrame[]>([]);
  const [lastSnapshot, setLastSnapshot] = useState<SnapshotResult | null>(null);

  const captureRef = useRef<VideoFrameCapture | null>(null);
  const trackIdRef = useRef(trackId);

  // 初始化捕获器
  useEffect(() => {
    captureRef.current = new VideoFrameCapture();
    trackIdRef.current = trackId;

    return () => {
      captureRef.current?.destroy();
    };
  }, []);

  // 设置回调
  useEffect(() => {
    const capture = captureRef.current;
    if (!capture) return;

    let unsubscribeFrame: (() => void) | undefined;
    let unsubscribeSnapshot: (() => void) | undefined;
    let unsubscribeError: (() => void) | undefined;

    if (onFrame) {
      unsubscribeFrame = capture.onFrame(trackIdRef.current, (frame) => {
        setFrames(prev => [...prev.slice(-29), frame]); // 保留最近 30 帧
        onFrame(frame);
      });
    }

    if (onSnapshot) {
      unsubscribeSnapshot = capture.onSnapshot(trackIdRef.current, (snapshot) => {
        setLastSnapshot(snapshot);
        onSnapshot(snapshot);
      });
    }

    if (onError) {
      unsubscribeError = capture.onError(onError);
    }

    return () => {
      unsubscribeFrame?.();
      unsubscribeSnapshot?.();
      unsubscribeError?.();
    };
  }, [onFrame, onSnapshot, onError]);

  // 自动开始
  useEffect(() => {
    if (autoStart && trackId) {
      startCapture();
    }
  }, [autoStart, trackId]);

  /**
   * 开始捕获
   */
  const startCapture = useCallback(async () => {
    const capture = captureRef.current;
    if (!capture) return;

    try {
      await capture.startCapture(trackIdRef.current, config);
      setIsCapturing(true);
    } catch (error) {
      console.error('Failed to start capture:', error);
    }
  }, [config]);

  /**
   * 停止捕获
   */
  const stopCapture = useCallback(async () => {
    const capture = captureRef.current;
    if (!capture) return;

    try {
      await capture.stopCapture(trackIdRef.current);
      setIsCapturing(false);
    } catch (error) {
      console.error('Failed to stop capture:', error);
    }
  }, []);

  /**
   * 截图
   */
  const captureSnapshot = useCallback(async (): Promise<SnapshotResult> => {
    const capture = captureRef.current;
    if (!capture) {
      throw new Error('Capture not initialized');
    }

    const result = await capture.captureFrame(trackIdRef.current);
    setLastSnapshot(result);
    return result;
  }, []);

  /**
   * 清除帧列表
   */
  const clearFrames = useCallback(() => {
    setFrames([]);
  }, []);

  /**
   * 更新配置
   */
  const updateConfig = useCallback(async (newConfig: FrameConfig) => {
    const capture = captureRef.current;
    if (!capture) return;

    try {
      await capture.updateConfig(trackIdRef.current, newConfig);
    } catch (error) {
      console.error('Failed to update config:', error);
    }
  }, []);

  return {
    isCapturing,
    frames,
    lastSnapshot,
    startCapture,
    stopCapture,
    captureSnapshot,
    clearFrames,
    updateConfig,
    capture: captureRef.current!
  };
}

/**
 * React Hook: useAIScreenAnalysis
 *
 * 用于 AI 屏幕分析的 Hook
 *
 * @example
 * import { useAIScreenAnalysis } from './useVideoFrameCapture';
 *
 * function AIAnalysisComponent({ trackId, model }) {
 *   const { analysisResults, isAnalyzing, processFrame } = useAIScreenAnalysis({
 *     trackId,
 *     model: yourAIModel,
 *     onResult: (result) => console.log('AI Result:', result)
 *   });
 *
 *   return (
 *     <View>
 *       {analysisResults.map((result, i) => (
 *         <Text key={i}>{JSON.stringify(result)}</Text>
 *       ))}
 *     </View>
 *   );
 * }
 */

interface AIAnalysisOptions {
  /** WebRTC 轨道 ID */
  trackId: string;
  /** AI 模型 */
  model: any;
  /** 分析间隔 (毫秒) */
  interval?: number;
  /** 分析回调 */
  onResult?: (result: any) => void;
  /** 错误回调 */
  onError?: (error: Error) => void;
}

interface AIAnalysisReturn {
  /** 是否正在分析 */
  isAnalyzing: boolean;
  /** 分析结果列表 */
  analysisResults: any[];
  /** 开始分析 */
  startAnalysis: () => Promise<void>;
  /** 停止分析 */
  stopAnalysis: () => void;
  /** 处理当前帧 */
  processFrame: (frame: VideoFrame) => Promise<void>;
  /** 清除结果 */
  clearResults: () => void;
}

export function useAIScreenAnalysis(options: AIAnalysisOptions): AIAnalysisReturn {
  const { trackId, model, interval = 1000, onResult, onError } = options;

  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [analysisResults, setAnalysisResults] = useState<any[]>([]);

  const captureRef = useRef<VideoFrameCapture | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastFrameRef = useRef<VideoFrame | null>(null);

  // 初始化
  useEffect(() => {
    captureRef.current = new VideoFrameCapture();

    // 监听帧
    captureRef.current.onFrame(trackId, (frame) => {
      lastFrameRef.current = frame;
    });

    return () => {
      captureRef.current?.destroy();
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [trackId]);

  /**
   * 开始分析
   */
  const startAnalysis = useCallback(async () => {
    const capture = captureRef.current;
    if (!capture) return;

    try {
      await capture.startCapture(trackId, { targetFrameRate: 30 });
      setIsAnalyzing(true);

      // 设置定时分析
      intervalRef.current = setInterval(async () => {
        const frame = lastFrameRef.current;
        if (!frame || !model) return;

        try {
          // 使用 AI 模型分析
          const result = await analyzeFrame(frame, model);
          setAnalysisResults(prev => [...prev.slice(-49), result]); // 保留最近 50 个结果
          onResult?.(result);
        } catch (error) {
          onError?.(error as Error);
        }
      }, interval);

    } catch (error) {
      console.error('Failed to start analysis:', error);
    }
  }, [trackId, model, interval, onResult, onError]);

  /**
   * 停止分析
   */
  const stopAnalysis = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    captureRef.current?.stopCapture(trackId);
    setIsAnalyzing(false);
  }, [trackId]);

  /**
   * 处理帧
   */
  const processFrame = useCallback(async (frame: VideoFrame) => {
    if (!model) return;

    try {
      const result = await analyzeFrame(frame, model);
      setAnalysisResults(prev => [...prev, result]);
      onResult?.(result);
      return result;
    } catch (error) {
      onError?.(error as Error);
      throw error;
    }
  }, [model, onResult, onError]);

  /**
   * 清除结果
   */
  const clearResults = useCallback(() => {
    setAnalysisResults([]);
  }, []);

  return {
    isAnalyzing,
    analysisResults,
    startAnalysis,
    stopAnalysis,
    processFrame,
    clearResults
  };
}

/**
 * AI 分析辅助函数
 */
async function analyzeFrame(frame: VideoFrame, model: any): Promise<any> {
  // 这里应该实现实际的 AI 分析逻辑
  // 假设 model 有一个 analyzeImage(data: string) 方法

  if (typeof model.analyzeImage === 'function') {
    return await model.analyzeImage(frame.data);
  }

  // 默认实现
  return {
    timestamp: frame.timestamp,
    width: frame.width,
    height: frame.height,
    // 添加你的 AI 分析逻辑
  };
}
