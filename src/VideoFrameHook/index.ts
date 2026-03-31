/**
 * VideoFrameHook - React Native WebRTC VideoFrame Hook 模块
 *
 * @example
 * import { VideoFrameCapture } from 'react-native-webrtc/VideoFrameHook';
 *
 * const capture = new VideoFrameCapture();
 * await capture.startCapture('track-id');
 * capture.onFrame('track-id', (frame) => {
 *   console.log('Frame received:', frame.width, frame.height);
 * });
 */

// 主模块
export {
  default as VideoFrameCapture,
  videoFrameCapture,
} from './VideoFrameCapture';

export type {
  FrameConfig,
  VideoFrame,
  SnapshotResult,
  FrameCallback,
  SnapshotCallback,
  ErrorCallback
} from './VideoFrameCapture';

// Hooks
export {
  useVideoFrameCapture,
  useAIScreenAnalysis
} from './useVideoFrameCapture';
