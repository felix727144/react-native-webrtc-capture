package com.oney.WebRTCModule.VideoFrameHook;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;

import org.webrtc.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * VideoFrame 捕获器 - 用于将 WebRTC VideoFrame 转换为 Bitmap
 * 支持实时截图、AI 分析、视频编码等场景
 *
 * 功能：
 * 1. YUV(I420) 到 Bitmap 转换
 * 2. YUV 到 RGBA 原始数据转换
 * 3. 实时帧捕获回调
 */
public class VideoFrameCapturer {
    private static final String TAG = "VideoFrameCapturer";

    private OnFrameCapturedListener listener;
    private Handler mainHandler;
    private boolean isCapturing = false;

    /**
     * 帧捕获监听器接口
     */
    public interface OnFrameCapturedListener {
        /**
         * 帧捕获回调
         * @param bitmap 捕获的 Bitmap 图像
         * @param timestampNs 时间戳（纳秒）
         * @param width 宽度
         * @param height 高度
         */
        void onFrameCaptured(Bitmap bitmap, long timestampNs, int width, int height);

        /**
         * 错误回调
         * @param error 错误信息
         */
        void onError(String error);
    }

    public VideoFrameCapturer() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置帧捕获监听器
     */
    public void setListener(@Nullable OnFrameCapturedListener listener) {
        this.listener = listener;
    }

    /**
     * 开始捕获
     */
    public void startCapture() {
        isCapturing = true;
    }

    /**
     * 停止捕获
     */
    public void stopCapture() {
        isCapturing = false;
    }

    /**
     * 是否正在捕获
     */
    public boolean isCapturing() {
        return isCapturing;
    }

    /**
     * 处理 VideoFrame - WebRTC 会调用此方法
     */
    public void onFrame(@NonNull VideoFrame frame) {
        Log.e(TAG, "===== VideoFrameCapture.onFrame start,isCapturing:"+isCapturing);

        if (!isCapturing || listener == null) {
            return;
        }

        // retain frame 以便异步使用
        frame.retain();

        try {
            // 在主线程回调
            mainHandler.post(() -> {
                Log.e(TAG, "===== VideoFrameCapture.onFrame: mainHandler.post ,isCapturing:"+isCapturing);

                if (!isCapturing) {
                    frame.release();
                    return;
                }

                try {
                    // 转换为 Bitmap
                    Bitmap bitmap = convertToBitmap(frame);
                    if (bitmap != null) {
                        Log.e(TAG, "===== convertToBitmap success: " + bitmap.getWidth() + "x" + bitmap.getHeight());

                        listener.onFrameCaptured(
                            bitmap,
                            //frame.getTimestampUs() * 1000,
                            0,
                            frame.getRotatedWidth(),
                            frame.getRotatedHeight()
                        );
                    }
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("Frame conversion error: " + e.getMessage());
                    }
                } finally {
                    // 使用完毕后释放
                    frame.release();
                }
            });
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("Frame processing error: " + e.getMessage());
            }
            frame.release();
        }
    }

    /**
     * 将 VideoFrame 转换为 Bitmap（使用 YUV 转 JPEG 方式）
     * 这是兼容性最好的转换方式，适用于截图和传输
     */
    public Bitmap convertToBitmap(VideoFrame frame) {
        VideoFrame.Buffer buffer = frame.getBuffer();
        VideoFrame.I420Buffer i420Buffer = null;

        try {
            // 转换为 I420 格式
            i420Buffer = buffer.toI420();

            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();

            // 获取 YUV 数据并复制出来
            byte[] yData = new byte[width * height];
            byte[] uData = new byte[width * height / 4];
            byte[] vData = new byte[width * height / 4];

            i420Buffer.getDataY().get(yData);
            i420Buffer.getDataU().get(uData);
            i420Buffer.getDataV().get(vData);

            // 创建 NV21 格式数据
            byte[] nv21 = new byte[width * height * 3 / 2];
            System.arraycopy(yData, 0, nv21, 0, width * height);

            // 交错排列 U 和 V
            int uvSize = width * height / 4;
            for (int i = 0; i < uvSize; i++) {
                nv21[width * height + i * 2] = vData[i];
                nv21[width * height + i * 2 + 1] = uData[i];
            }

            // 创建 YuvImage
            YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            );

            // 压缩为 JPEG
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            boolean success = yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                90,
                outputStream
            );

            if (!success) {
                return null;
            }

            byte[] jpegData = outputStream.toByteArray();

            // 解码为 Bitmap
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        } catch (Exception e) {
            Log.e(TAG, "convertToBitmap error: " + e.getMessage(), e);
            return null;
        } finally {
            // 释放 I420Buffer (toI420() 会增加引用计数)
            if (i420Buffer != null) {
                i420Buffer.release();
            }
        }
    }

    /**
     * 将 VideoFrame 直接转换为 ARGB Bitmap（更快、无损）
     * 适用于 AI 分析和视频处理
     */
    public Bitmap convertToARGB(@NonNull VideoFrame frame) {
        VideoFrame.Buffer buffer = frame.getBuffer();
        VideoFrame.I420Buffer i420 = null;

        try {
            i420 = buffer.toI420();

            int width = i420.getWidth();
            int height = i420.getHeight();

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // YUV 到 ARGB 转换
            int[] argb = new int[width * height];

            byte[] yData = new byte[width * height];
            byte[] uData = new byte[(width / 2) * (height / 2)];
            byte[] vData = new byte[(width / 2) * (height / 2)];

            i420.getDataY().get(yData);
            i420.getDataU().get(uData);
            i420.getDataV().get(vData);

            // BT.601 YUV to RGB 转换公式
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    int yIndex = j * width + i;
                    int uvIndex = (j / 2) * (width / 2) + (i / 2);

                    int y = yData[yIndex] & 0xff;
                    int u = uData[uvIndex] & 0xff;
                    int v = vData[uvIndex] & 0xff;

                    // BT.601 YUV to RGB 转换
                    int r = (int) (y + 1.402 * (v - 128));
                    int g = (int) (y - 0.344 * (u - 128) - 0.714 * (v - 128));
                    int b = (int) (y + 1.772 * (u - 128));

                    // 范围限制
                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));

                    // ARGB 格式：Alpha=255, R, G, B
                    argb[yIndex] = (0xff << 24) | (r << 16) | (g << 8) | b;
                }
            }

            bitmap.setPixels(argb, 0, width, 0, 0, width, height);

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "convertToARGB error: " + e.getMessage(), e);
            return null;
        } finally {
            if (i420 != null) {
                i420.release();
            }
        }
    }

    /**
     * 将 VideoFrame 转换为原始 RGBA 字节数组（用于 AI 处理）
     * 返回格式：RGBA RGBA RGBA ... (每个像素 4 字节)
     */
    public byte[] convertToRawBytes(@NonNull VideoFrame frame) {
        VideoFrame.Buffer buffer = frame.getBuffer();
        VideoFrame.I420Buffer i420 = null;

        try {
            i420 = buffer.toI420();

            int width = i420.getWidth();
            int height = i420.getHeight();

            // 直接返回 RGBA 字节数据
            byte[] rgba = new byte[width * height * 4];

            byte[] yData = new byte[width * height];
            byte[] uData = new byte[(width / 2) * (height / 2)];
            byte[] vData = new byte[(width / 2) * (height / 2)];

            i420.getDataY().get(yData);
            i420.getDataU().get(uData);
            i420.getDataV().get(vData);

            int pixelIndex = 0;
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    int yIndex = j * width + i;
                    int uvIndex = (j / 2) * (width / 2) + (i / 2);

                    int y = yData[yIndex] & 0xff;
                    int u = uData[uvIndex] & 0xff;
                    int v = vData[uvIndex] & 0xff;

                    int r = (int) (y + 1.402 * (v - 128));
                    int g = (int) (y - 0.344 * (u - 128) - 0.714 * (v - 128));
                    int b = (int) (y + 1.772 * (u - 128));

                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));

                    rgba[pixelIndex++] = (byte) r;
                    rgba[pixelIndex++] = (byte) g;
                    rgba[pixelIndex++] = (byte) b;
                    rgba[pixelIndex++] = (byte) 255;
                }
            }

            return rgba;
        } catch (Exception e) {
            Log.e(TAG, "convertToRawBytes error: " + e.getMessage(), e);
            return null;
        } finally {
            if (i420 != null) {
                i420.release();
            }
        }
    }

    /**
     * 将 VideoFrame 转换为 YUV 原始数据（用于视频编码）
     * 返回格式：Y + U + V (平面格式)
     */
    public byte[] convertToYUV(@NonNull VideoFrame frame) {
        VideoFrame.Buffer buffer = frame.getBuffer();
        VideoFrame.I420Buffer i420 = null;

        try {
            i420 = buffer.toI420();

            int width = i420.getWidth();
            int height = i420.getHeight();

            byte[] yData = new byte[width * height];
            byte[] uData = new byte[(width / 2) * (height / 2)];
            byte[] vData = new byte[(width / 2) * (height / 2)];

            i420.getDataY().get(yData);
            i420.getDataU().get(uData);
            i420.getDataV().get(vData);

            // 合并为 I420 平面格式
            byte[] yuv = new byte[yData.length + uData.length + vData.length];
            System.arraycopy(yData, 0, yuv, 0, yData.length);
            System.arraycopy(uData, 0, yuv, yData.length, uData.length);
            System.arraycopy(vData, 0, yuv, yData.length + uData.length, vData.length);

            return yuv;
        } catch (Exception e) {
            Log.e(TAG, "convertToYUV error: " + e.getMessage(), e);
            return null;
        } finally {
            if (i420 != null) {
                i420.release();
            }
        }
    }
}
