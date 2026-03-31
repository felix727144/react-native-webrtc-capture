package com.oney.WebRTCModule.VideoFrameHook;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.oney.WebRTCModule.WebRTCModule;

import org.webrtc.MediaStream;
import org.webrtc.VideoTrack;
import org.webrtc.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ReactModule(name = "VideoFrameCaptureModule")
public class VideoFrameCaptureModule extends ReactContextBaseJavaModule {
    private static final String TAG = "VideoFrameCaptureModule";
    private final ReactApplicationContext reactContext;
    private final Map<String, VideoFrameAdapter> activeAdapters = new ConcurrentHashMap<>();
    private final Map<String, VideoFrameAdapter.OnVideoFrameListener> frameCallbacks = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VideoFrameCaptureModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "VideoFrameCaptureModule";
    }

    @ReactMethod
    public void startCapture(String trackId, @Nullable ReadableMap config, Promise promise) {
        Log.e(TAG, "===== startCapture ENTER: " + trackId);
        try {
            Log.e(TAG, "===== startCapture: WebRTCModule.getInstance() = " + (WebRTCModule.getInstance() != null));
            
            // 通过 WebRTCModule 获取 VideoTrack
            VideoTrack targetTrack = getVideoTrack(trackId);
            Log.e(TAG, "===== startCapture: targetTrack = " + (targetTrack != null));
            
            if (targetTrack == null) {
                Log.e(TAG, "===== startCapture: Video track not found, rejecting promise");
                promise.reject("NOT_FOUND", "Video track not found: " + trackId);
                return;
            }

            VideoFrameAdapter adapter = new VideoFrameAdapter();
            VideoFrameAdapter.OnVideoFrameListener listener = frame -> handleFrame(trackId, frame);

            frameCallbacks.put(trackId, listener);
            adapter.addFrameListener(listener);

            final VideoTrack finalTrack = targetTrack;
            mainHandler.post(() -> finalTrack.addSink(adapter));

            activeAdapters.put(trackId, adapter);
            Log.e(TAG, "===== startCapture: activeAdapters.size = " + activeAdapters.size());
            Log.e(TAG, "===== startCapture: activeAdapters.keys = " + activeAdapters.keySet());

            WritableMap result = Arguments.createMap();
            result.putBoolean("success", true);
            result.putString("trackId", trackId);
            Log.e(TAG, "===== startCapture: resolving promise with result = " + result.toString());
            promise.resolve(result);
            Log.e(TAG, "===== startCapture: EXIT (success)");

        } catch (Exception e) {
            Log.e(TAG, "startCapture error", e);
            String errorMsg = e.getMessage();
            promise.reject("CAPTURE_ERROR", errorMsg != null ? errorMsg : "Unknown startCapture error");
            Log.e(TAG, "===== startCapture: EXIT (error)");
        }
    }

    @ReactMethod
    public void stopCapture(String trackId, Promise promise) {
        try {
            VideoFrameAdapter adapter = activeAdapters.remove(trackId);
            if (adapter != null) {
                VideoTrack track = getVideoTrack(trackId);
                if (track != null) mainHandler.post(() -> track.removeSink(adapter));
                
                VideoFrameAdapter.OnVideoFrameListener listener = frameCallbacks.remove(trackId);
                if (listener != null) adapter.removeFrameListener(listener);
                adapter.release();
            }
            
            WritableMap result = Arguments.createMap();
            result.putBoolean("success", true);
            result.putString("trackId", trackId);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("CAPTURE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void captureFrame(String trackId, Promise promise) {
        Log.e(TAG, "===== captureFrame ENTER: " + trackId);
        try {
            Log.e(TAG, "===== captureFrame: " + trackId);
            Log.e(TAG, "===== captureFrame: activeAdapters count = " + activeAdapters.size());
            Log.e(TAG, "===== captureFrame: activeAdapters keys = " + activeAdapters.keySet());

            VideoFrameAdapter adapter = activeAdapters.get(trackId);
            Log.e(TAG, "===== captureFrame: adapter found = " + (adapter != null ? "yes" : "no"));

            if (adapter == null) {
                Log.e(TAG, "===== captureFrame: No active capture, rejecting promise");
                promise.reject("NOT_FOUND", "No active capture for track: " + trackId);
                return;
            }

            Log.e(TAG, "===== captureFrame: Setting snapshot listener...");
            adapter.setSnapshotListener((bitmap, timestampNs) -> {
                Log.e(TAG, "===== captureFrame: Snapshot listener called: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                WritableMap result = createSnapshotResult(bitmap, timestampNs);
                adapter.setSnapshotListener(null);
                Log.e(TAG, "===== captureFrame: resolving promise");
                promise.resolve(result);
            });

            Log.e(TAG, "===== captureFrame: Requesting snapshot...");
            adapter.requestSnapshot();
            Log.e(TAG, "===== captureFrame: EXIT (waiting for callback)");

        } catch (Exception e) {
            Log.e(TAG, "captureFrame error", e);
            String errorMsg = e.getMessage();
            promise.reject("CAPTURE_ERROR", errorMsg != null ? errorMsg : "Unknown capture error");
            Log.e(TAG, "===== captureFrame: EXIT (error)");
        }
    }

    @ReactMethod
    public void stopAllCaptures(Promise promise) {
        try {
            for (String trackId : activeAdapters.keySet()) {
                VideoFrameAdapter adapter = activeAdapters.remove(trackId);
                if (adapter != null) {
                    VideoTrack track = getVideoTrack(trackId);
                    if (track != null) mainHandler.post(() -> track.removeSink(adapter));

                    VideoFrameAdapter.OnVideoFrameListener listener = frameCallbacks.remove(trackId);
                    if (listener != null) adapter.removeFrameListener(listener);
                    adapter.release();
                }
            }

            WritableMap result = Arguments.createMap();
            result.putBoolean("success", true);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("CAPTURE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getFrameData(String trackId, String format, Promise promise) {
        try {
            Log.e(TAG, "===== getFrameData: " + trackId + ", format: " + format);

            VideoFrameAdapter adapter = activeAdapters.get(trackId);
            if (adapter == null) {
                promise.reject("NOT_FOUND", "No active capture for track: " + trackId);
                return;
            }

            adapter.setSnapshotListener((bitmap, timestampNs) -> {
                WritableMap result = createFrameDataResult(bitmap, timestampNs, format);
                adapter.setSnapshotListener(null);
                promise.resolve(result);
            });
            adapter.requestSnapshot();

        } catch (Exception e) {
            Log.e(TAG, "getFrameData error", e);
            promise.reject("CAPTURE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void updateConfig(String trackId, ReadableMap config, Promise promise) {
        try {
            Log.e(TAG, "===== updateConfig: " + trackId);

            VideoFrameAdapter adapter = activeAdapters.get(trackId);
            if (adapter == null) {
                promise.reject("NOT_FOUND", "No active capture for track: " + trackId);
                return;
            }

            // 更新配置
            if (config.hasKey("targetFrameRate")) {
                adapter.getCaptureConfig().targetFrameRate = config.getInt("targetFrameRate");
            }
            if (config.hasKey("scale")) {
                adapter.getCaptureConfig().scale = (float) config.getDouble("scale");
            }
            if (config.hasKey("quality")) {
                adapter.getCaptureConfig().quality = config.getInt("quality");
            }

            WritableMap result = Arguments.createMap();
            result.putBoolean("success", true);
            result.putString("trackId", trackId);
            promise.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "updateConfig error", e);
            promise.reject("CAPTURE_ERROR", e.getMessage());
        }
    }

    private VideoTrack getVideoTrack(String trackId) {
        try {
            WebRTCModule webRTCModule = WebRTCModule.getInstance();
            if (webRTCModule == null) return null;
            
            Map<String, MediaStream> localStreams = webRTCModule.getLocalStreams();
            for (MediaStream stream : localStreams.values()) {
                for (VideoTrack track : stream.videoTracks) {
                    if (track.id().equals(trackId)) {
                        return track;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getVideoTrack error", e);
        }
        return null;
    }

    private void handleFrame(String trackId, VideoFrame frame) {
        // Handle frame if needed
    }

    private WritableMap createSnapshotResult(Bitmap bitmap, long timestampNs) {
        WritableMap result = Arguments.createMap();
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);

            result.putBoolean("success", true);
            result.putDouble("timestamp", timestampNs / 1_000_000.0);
            result.putInt("width", bitmap.getWidth());
            result.putInt("height", bitmap.getHeight());
            result.putString("data", Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));

            bitmap.recycle();
        } catch (Exception e) {
            result.putBoolean("success", false);
            result.putString("error", e.getMessage());
        }
        return result;
    }

    private WritableMap createFrameDataResult(Bitmap bitmap, long timestampNs, String format) {
        WritableMap result = Arguments.createMap();
        try {
            result.putBoolean("success", true);
            result.putDouble("timestamp", timestampNs / 1_000_000.0);
            result.putInt("width", bitmap.getWidth());
            result.putInt("height", bitmap.getHeight());
            result.putString("format", format);

            if ("bitmap".equals(format)) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                result.putString("data", Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));
            } else if ("rgba".equals(format) || "rawData".equals(format)) {
                // 返回 RGBA 原始数据（Base64 编码）
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                byte[] rgba = new byte[pixels.length * 4];
                for (int i = 0; i < pixels.length; i++) {
                    int pixel = pixels[i];
                    rgba[i * 4] = (byte) ((pixel >> 16) & 0xFF);     // R
                    rgba[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF);  // G
                    rgba[i * 4 + 2] = (byte) (pixel & 0xFF);         // B
                    rgba[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF); // A
                }
                result.putString("data", Base64.encodeToString(rgba, Base64.NO_WRAP));
            } else if ("yuv".equals(format)) {
                // 返回 YUV 数据（Base64 编码）- 简化版本
                result.putString("error", "YUV format not implemented");
                result.putBoolean("success", false);
            }

            bitmap.recycle();
        } catch (Exception e) {
            result.putBoolean("success", false);
            result.putString("error", e.getMessage());
        }
        return result;
    }
}
