package com.oney.WebRTCModule.VideoFrameHook;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.EglBase;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * VideoFrame 适配器 - Hook WebRTC 视频帧
 *
 * 用途：
 * 1. 拦截视频帧进行 AI 分析
 * 2. 实时截图
 * 3. 视频编码处理
 * 4. 自定义视频滤镜
 *
 * 使用方法：
 * 1. 创建 VideoFrameAdapter 实例
 * 2. 添加帧监听器处理帧
 * 3. 将其添加到 VideoTrack 的 sink 中
 * 4. 原始渲染器的 sink 也需要设置以便视频正常显示
 */
public class VideoFrameAdapter implements VideoSink {

    private static final String TAG = "VideoFrameAdapter";

    // 原始的 VideoSink（用于渲染）
    @Nullable
    private VideoSink originalSink;

    // 帧回调监听器列表
    private final CopyOnWriteArrayList<OnVideoFrameListener> frameListeners = new CopyOnWriteArrayList<>();

    // 截图回调
    @Nullable
    private OnSnapshotListener snapshotListener;

    // 是否启用帧捕获
    private volatile boolean frameCaptureEnabled = true;

    // 帧捕获配置
    private FrameCaptureConfig captureConfig = new FrameCaptureConfig();

    // EGL 上下文（用于 OpenGL 渲染）
    @Nullable
    private EglBase eglBase;

    // 主线程 Handler
    private android.os.Handler mainHandler;

    public VideoFrameAdapter() {
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    /**
     * 设置原始 VideoSink（渲染目标）
     */
    public void setOriginalSink(@Nullable VideoSink sink) {
        this.originalSink = sink;
    }

    /**
     * 获取原始 VideoSink
     */
    @Nullable
    public VideoSink getOriginalSink() {
        return originalSink;
    }

    /**
     * 设置 EGL 上下文
     */
    public void setEglBase(@Nullable EglBase eglBase) {
        this.eglBase = eglBase;
    }

    /**
     * 添加视频帧监听器
     */
    public void addFrameListener(@NonNull OnVideoFrameListener listener) {
        if (!frameListeners.contains(listener)) {
            frameListeners.add(listener);
        }
    }

    /**
     * 移除视频帧监听器
     */
    public void removeFrameListener(@NonNull OnVideoFrameListener listener) {
        frameListeners.remove(listener);
    }

    /**
     * 清空所有监听器
     */
    public void clearFrameListeners() {
        frameListeners.clear();
    }

    /**
     * 设置截图监听器
     */
    public void setSnapshotListener(@Nullable OnSnapshotListener listener) {
        this.snapshotListener = listener;
    }

    /**
     * 启用/禁用帧捕获
     */
    public void setFrameCaptureEnabled(boolean enabled) {
        this.frameCaptureEnabled = enabled;
    }

    /**
     * 获取帧捕获配置
     */
    public FrameCaptureConfig getCaptureConfig() {
        return captureConfig;
    }

    /**
     * 设置帧捕获配置
     */
    public void setCaptureConfig(@NonNull FrameCaptureConfig config) {
        this.captureConfig = config;
    }

    /**
     * 请求截图
     * 调用此方法后，下一帧会被捕获并通过 snapshotListener 返回
     */
    public void requestSnapshot() {
        captureConfig.requestSnapshot = true;
    }

    /**
     * 请求指定时间戳的帧
     */
    public void requestFrameAt(long timestampNs) {
        captureConfig.targetTimestampNs = timestampNs;
        captureConfig.timestampMatchEnabled = true;
    }

    @Override
    public void onFrame(@NonNull VideoFrame frame) {
        // 检查是否需要捕获帧
        boolean shouldCapture = frameCaptureEnabled && !frameListeners.isEmpty();
        boolean shouldSnapshot = false;
        Log.e(TAG, "===== onFrame called: " + frame.getRotatedWidth() + "x" + frame.getRotatedHeight());
        Log.e(TAG, "===== onFrame: shouldSnapshot=" + shouldSnapshot + ", requestSnapshot=" + captureConfig.requestSnapshot);

        if (captureConfig.requestSnapshot) {
            shouldSnapshot = true;
            shouldCapture = true;
            captureConfig.requestSnapshot = false;
            Log.e(TAG, "===== onFrame: snapshot requested");
        }

        if (captureConfig.timestampMatchEnabled) {
            //  captureConfig.targetTimestampNs == frame.getTimestampUs() * 1000) {
            shouldSnapshot = true;
            shouldCapture = true;
            captureConfig.timestampMatchEnabled = false;
        }

        // 转发帧到原始 Sink（渲染）
        if (originalSink != null) {
            try {
                originalSink.onFrame(frame);
            } catch (Exception e) {
                Log.e(TAG, "Error forwarding frame to original sink", e);
            }
        }

        // 通知帧监听器
        if (shouldCapture) {
            notifyFrameListeners(frame);
        }

        // 处理截图请求
        if (shouldSnapshot && snapshotListener != null) {
            Log.e(TAG, "===== onFrame: calling snapshot");
            mainHandler.post(() -> {
                VideoFrameCapturer capturer = new VideoFrameCapturer();
                capturer.setListener(new VideoFrameCapturer.OnFrameCapturedListener() {
                    @Override
                    public void onFrameCaptured(android.graphics.Bitmap bitmap, long ts, int w, int h) {
                        Log.e(TAG, "===== Snapshot captured: " + w + "x" + h);
                        if (snapshotListener != null) {
                            snapshotListener.onSnapshot(bitmap, ts);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Snapshot error: " + error);
                    }
                });
                capturer.onFrame(frame);
            });
        }
    }

    /**
     * 通知所有帧监听器
     */
    private void notifyFrameListeners(@NonNull VideoFrame frame) {
        for (OnVideoFrameListener listener : frameListeners) {
            try {
                listener.onVideoFrame(frame);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying frame listener", e);
            }
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        frameListeners.clear();
        snapshotListener = null;
        originalSink = null;
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    /**
     * 视频帧监听器接口
     */
    public interface OnVideoFrameListener {
        /**
         * 收到视频帧回调
         * @param frame WebRTC 视频帧
         */
        void onVideoFrame(@NonNull VideoFrame frame);
    }

    /**
     * 截图监听器接口
     */
    public interface OnSnapshotListener {
        /**
         * 截图完成回调
         * @param bitmap 截图的 Bitmap
         * @param timestampNs 帧时间戳
         */
        void onSnapshot(@NonNull android.graphics.Bitmap bitmap, long timestampNs);
    }

    /**
     * 帧捕获配置
     */
    public static class FrameCaptureConfig {
        // 是否请求截图
        public volatile boolean requestSnapshot = false;

        // 时间戳匹配模式
        public volatile boolean timestampMatchEnabled = false;
        public volatile long targetTimestampNs = 0;

        // 目标帧率（0 表示全部）
        public int targetFrameRate = 0;

        // 缩放比例（1.0 表示原始大小）
        public float scale = 1.0f;

        // 质量（0-100）
        public int quality = 90;

        // 格式
        public CaptureFormat format = CaptureFormat.JPEG;
    }

    /**
     * 捕获格式
     */
    public enum CaptureFormat {
        JPEG,
        PNG,
        ARGB
    }
}
