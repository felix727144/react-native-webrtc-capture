//
//  VideoFrameAdapter.m
//  react-native-webrtc
//
//  VideoFrame 适配器实现
//

#import "VideoFrameAdapter.h"

@interface VideoFrameAdapter () <VideoFrameCapturerDelegate>
@property (nonatomic, strong, readwrite) NSString *trackId;
@property (nonatomic, assign) BOOL snapshotRequested;
@property (nonatomic, strong) dispatch_queue_t processingQueue;
@end

@implementation VideoFrameAdapter

- (instancetype)initWithTrackId:(NSString *)trackId {
    self = [super init];
    if (self) {
        _trackId = trackId;
        _frameCaptureEnabled = YES;
        _snapshotRequested = NO;
        _processingQueue = dispatch_queue_create("com.reactnativewebrtc.videoframeadapter", DISPATCH_QUEUE_SERIAL);

        // 创建帧捕获器
        _capturer = [[VideoFrameCapturer alloc] initWithTrackId:trackId];
        _capturer.delegate = self;
        [_capturer startCapturing];
    }
    return self;
}

- (void)dealloc {
    [self disableFrameCapture];
}

- (void)enableFrameCapture {
    self.frameCaptureEnabled = YES;
    [self.capturer startCapturing];
}

- (void)disableFrameCapture {
    self.frameCaptureEnabled = NO;
    [self.capturer stopCapturing];
}

- (void)requestSnapshot {
    self.snapshotRequested = YES;
}

- (void)setTargetFrameRate:(int)frameRate {
    self.capturer.targetFrameRate = frameRate;
}

- (void)setCaptureFormat:(VideoFrameCaptureFormat)format {
    self.capturer.captureFormat = format;
}

#pragma mark - RTCVideoFrameAdapterDelegate

- (void)didOutputVideoFrame:(RTCVideoFrame *)frame {
    // 检查是否需要截图
    BOOL shouldSnapshot = self.snapshotRequested;
    if (shouldSnapshot) {
        self.snapshotRequested = NO;
    }

    // 转发帧到原始渲染器
    if (self.originalRenderer) {
        [self.originalRenderer renderFrame:frame];
    }

    // 通知代理
    if ([self.delegate respondsToSelector:@selector(adapter:didReceiveVideoFrame:)]) {
        [self.delegate adapter:self didReceiveVideoFrame:frame];
    }

    // 处理截图
    if (shouldSnapshot) {
        UIImage *image = [self.capturer createImageFromFrame:frame];
        if (image) {
            if ([self.delegate respondsToSelector:@selector(adapter:didCaptureSnapshot:timestamp:)]) {
                [self.delegate adapter:self didCaptureSnapshot:image timestamp:frame.timestampUs * 1000];
            }
        }
    }

    // 如果启用了帧捕获，处理帧
    if (self.frameCaptureEnabled) {
        [self.capturer processFrame:frame];
    }
}

#pragma mark - VideoFrameCapturerDelegate

- (void)capturer:(VideoFrameCapturer *)capturer didCaptureVideoFrame:(RTCVideoFrame *)frame {
    // 可以在这里添加额外的帧处理逻辑
}

- (void)capturer:(VideoFrameCapturer *)capturer didCaptureImage:(UIImage *)image timestamp:(int64_t)timestamp {
    // 图片捕获完成
}

- (void)capturer:(VideoFrameCapturer *)capturer didCapturePixelBuffer:(CVPixelBufferRef)pixelBuffer timestamp:(int64_t)timestamp {
    // Pixel buffer 捕获完成
}

- (void)capturer:(VideoFrameCapturer *)capturer didCaptureRawData:(NSData *)rawData width:(int)width height:(int)height timestamp:(int64_t)timestamp {
    // 原始数据捕获完成
}

- (void)capturer:(VideoFrameCapturer *)capturer didFailWithError:(NSError *)error {
    NSLog(@"VideoFrameCapturer error: %@", error.localizedDescription);
}

@end
