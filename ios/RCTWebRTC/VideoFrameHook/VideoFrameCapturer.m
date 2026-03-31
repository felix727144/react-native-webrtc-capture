//
//  VideoFrameCapturer.m
//  react-native-webrtc
//
//  VideoFrame 捕获器实现 - 将 WebRTC 视频帧转换为图像数据
//

#import "VideoFrameCapturer.h"
#import <CoreImage/CoreImage.h>
#import <Accelerate/Accelerate.h>

@interface VideoFrameCapturer ()
@property (nonatomic, strong) dispatch_queue_t processingQueue;
@property (nonatomic, assign) int64_t lastCaptureTime;
@property (nonatomic, strong) NSLock *lock;
@end

@implementation VideoFrameCapturer

- (instancetype)initWithTrackId:(NSString *)trackId {
    self = [super init];
    if (self) {
        _trackId = trackId;
        _captureEnabled = YES;
        _captureFormat = VideoFrameCaptureFormat_UIImage;
        _targetFrameRate = 30;
        _processingQueue = dispatch_queue_create("com.reactnativewebrtc.videocapture", DISPATCH_QUEUE_SERIAL);
        _lock = [[NSLock alloc] init];
        _lastCaptureTime = 0;
    }
    return self;
}

- (void)dealloc {
    [self stopCapturing];
}

- (void)startCapturing {
    self.captureEnabled = YES;
    self.lastCaptureTime = 0;
}

- (void)stopCapturing {
    [self.lock lock];
    self.captureEnabled = NO;
    [self.lock unlock];
}

#pragma mark - Frame Capture Methods

- (void)captureSnapshotWithCompletion:(void (^)(UIImage * _Nullable, NSError * _Nullable))completion {
    // 快照会通过 delegate 回调返回
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.1 * NSEC_PER_SEC)), self.processingQueue, ^{
        if (completion) {
            completion(nil, [NSError errorWithDomain:@"VideoFrameCapturer"
                                                code:-1
                                            userInfo:@{NSLocalizedDescriptionKey: @"Snapshot requested, result will be delivered via delegate"}]);
        }
    });
}

- (nullable UIImage *)captureSnapshot {
    // 同步截图需要保存最后一帧，这里返回 nil
    return nil;
}

#pragma mark - Data Conversion Methods

- (nullable CVPixelBufferRef)createPixelBufferFromFrame:(RTCVideoFrame *)frame {
    int width = frame.width;
    int height = frame.height;

    // 创建 Pixel Buffer
    NSDictionary *options = @{
        (NSString *)kCVPixelBufferCGImageCompatibilityKey: @YES,
        (NSString *)kCVPixelBufferCGBitmapContextCompatibilityKey: @YES,
        (NSString *)kCVPixelBufferMetalCompatibilityKey: @YES
    };

    CVPixelBufferRef pixelBuffer;
    CVReturn status = CVPixelBufferCreate(
        kCFAllocatorDefault,
        width,
        height,
        kCVPixelFormatType_32BGRA,
        (__bridge CFDictionaryRef)options,
        &pixelBuffer
    );

    if (status != kCVReturnSuccess) {
        return NULL;
    }

    // 获取 YUV 数据
    RTCVideoFrameBuffer *videoFrameBuffer = (RTCVideoFrameBuffer *)frame.buffer;
    id<RTCVideoFrameBufferProtocol> yuvFrame = [videoFrameBuffer toI420];

    const uint8_t *yPlane = yuvFrame.dataY;
    const uint8_t *uPlane = yuvFrame.dataU;
    const uint8_t *vPlane = yuvFrame.dataV;

    int yStride = yuvFrame.strideY;
    int uStride = yuvFrame.strideU;
    int vStride = yuvFrame.strideV;

    // 锁定 Pixel Buffer
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);

    // YUV 到 BGRA 转换
    uint8_t *destBuffer = (uint8_t *)baseAddress;

    // YUV to BGRA 转换
    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
            int yIndex = j * yStride + i;
            int uvIndex = (j / 2) * uStride + (i / 2);

            int y = yPlane[yIndex];
            int u = uPlane[uvIndex] - 128;
            int v = vPlane[uvIndex] - 128;

            // BT.601 YUV to RGB 转换
            int r = y + (int)(1.402 * v);
            int g = y - (int)(0.344 * u) - (int)(0.714 * v);
            int b = y + (int)(1.772 * u);

            // 范围限制
            r = MAX(0, MIN(255, r));
            g = MAX(0, MIN(255, g));
            b = MAX(0, MIN(255, b));

            // BGRA 格式
            int pixelIndex = j * width * 4 + i * 4;
            destBuffer[pixelIndex + 0] = b;      // B
            destBuffer[pixelIndex + 1] = g;      // G
            destBuffer[pixelIndex + 2] = r;      // R
            destBuffer[pixelIndex + 3] = 255;   // A
        }
    }

    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    // 释放 I420 buffer
    [yuvFrame release];

    return pixelBuffer;
}

- (void)convertYUVToRGB:(const uint8_t *)yPlane
                 uPlane:(const uint8_t *)uPlane
                 vPlane:(const uint8_t *)vPlane
              yStride:(int)yStride
              uStride:(int)uStride
              vStride:(int)vStride
                width:(int)width
               height:(int)height
            outputBuffer:(uint8_t *)outputBuffer {

    // YUV to BGRA 转换
    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
            int yIndex = j * yStride + i;
            int uvIndex = (j / 2) * uStride + (i / 2);

            int y = yPlane[yIndex];
            int u = uPlane[uvIndex] - 128;
            int v = vPlane[uvIndex] - 128;

            // BT.601 YUV to RGB 转换
            int r = y + (int)(1.402 * v);
            int g = y - (int)(0.344 * u) - (int)(0.714 * v);
            int b = y + (int)(1.772 * u);

            // 范围限制
            r = MAX(0, MIN(255, r));
            g = MAX(0, MIN(255, g));
            b = MAX(0, MIN(255, b));

            // BGRA 格式
            int pixelIndex = j * width * 4 + i * 4;
            outputBuffer[pixelIndex + 0] = b;      // B
            outputBuffer[pixelIndex + 1] = g;      // G
            outputBuffer[pixelIndex + 2] = r;      // R
            outputBuffer[pixelIndex + 3] = 255;   // A
        }
    }
}

- (nullable UIImage *)createImageFromFrame:(RTCVideoFrame *)frame {
    CVPixelBufferRef pixelBuffer = [self createPixelBufferFromFrame:frame];
    if (!pixelBuffer) {
        return nil;
    }

    CIImage *ciImage = [CIImage imageWithCVPixelBuffer:pixelBuffer];

    CIContext *context = [CIContext contextWithOptions:@{kCIContextUseSoftwareRenderer: @NO}];
    CGImageRef cgImage = [context createCGImage:ciImage fromRect:ciImage.extent];

    if (!cgImage) {
        CVPixelBufferRelease(pixelBuffer);
        return nil;
    }

    UIImage *image = [UIImage imageWithCGImage:cgImage];

    CGImageRelease(cgImage);
    CVPixelBufferRelease(pixelBuffer);

    return image;
}

- (nullable NSData *)createRawRGBAFromFrame:(RTCVideoFrame *)frame {
    int width = frame.width;
    int height = frame.height;

    // 创建 RGBA 数据
    NSMutableData *rgbaData = [NSMutableData dataWithLength:width * height * 4];

    // 获取 YUV 数据
    RTCVideoFrameBuffer *videoFrameBuffer = (RTCVideoFrameBuffer *)frame.buffer;
    id<RTCVideoFrameBufferProtocol> yuvFrame = [videoFrameBuffer toI420];

    const uint8_t *yPlane = yuvFrame.dataY;
    const uint8_t *uPlane = yuvFrame.dataU;
    const uint8_t *vPlane = yuvFrame.dataV;

    int yStride = yuvFrame.strideY;
    int uStride = yuvFrame.strideU;
    int vStride = yuvFrame.strideV;

    uint8_t *outputBuffer = (uint8_t *)[rgbaData mutableBytes];

    // YUV to RGBA 转换
    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
            int yIndex = j * yStride + i;
            int uvIndex = (j / 2) * uStride + (i / 2);

            int y = yPlane[yIndex];
            int u = uPlane[uvIndex] - 128;
            int v = vPlane[uvIndex] - 128;

            int r = y + (int)(1.402 * v);
            int g = y - (int)(0.344 * u) - (int)(0.714 * v);
            int b = y + (int)(1.772 * u);

            r = MAX(0, MIN(255, r));
            g = MAX(0, MIN(255, g));
            b = MAX(0, MIN(255, b));

            int pixelIndex = (j * width + i) * 4;
            outputBuffer[pixelIndex + 0] = r;     // R
            outputBuffer[pixelIndex + 1] = g;     // G
            outputBuffer[pixelIndex + 2] = b;     // B
            outputBuffer[pixelIndex + 3] = 255;   // A
        }
    }

    [yuvFrame release];

    return rgbaData;
}

- (nullable NSString *)createBase64FromFrame:(RTCVideoFrame *)frame {
    UIImage *image = [self createImageFromFrame:frame];
    if (!image) {
        return nil;
    }

    NSData *imageData = UIImageJPEGRepresentation(image, 0.8);
    NSString *base64 = [imageData base64EncodedStringWithOptions:NSDataBase64EncodingEndLineWithLineFeed];

    return base64;
}

#pragma mark - Frame Processing

- (void)processFrame:(RTCVideoFrame *)frame {
    if (!self.captureEnabled) {
        return;
    }

    // 帧率控制
    int64_t currentTime = frame.timestampUs * 1000; // 转换为纳秒
    int minInterval = 1000000000 / self.targetFrameRate; // 纳秒

    [self.lock lock];
    if (currentTime - self.lastCaptureTime < minInterval) {
        [self.lock unlock];
        return;
    }
    self.lastCaptureTime = currentTime;
    [self.lock unlock];

    int64_t timestamp = frame.timestampUs * 1000; // 毫秒

    switch (self.captureFormat) {
        case VideoFrameCaptureFormat_UIImage: {
            UIImage *image = [self createImageFromFrame:frame];
            if (image && [self.delegate respondsToSelector:@selector(capturer:didCaptureImage:timestamp:)]) {
                [self.delegate capturer:self didCaptureImage:image timestamp:timestamp];
            }
            break;
        }

        case VideoFrameCaptureFormat_PixelBuffer: {
            CVPixelBufferRef pixelBuffer = [self createPixelBufferFromFrame:frame];
            if (pixelBuffer) {
                if ([self.delegate respondsToSelector:@selector(capturer:didCapturePixelBuffer:timestamp:)]) {
                    [self.delegate capturer:self didCapturePixelBuffer:pixelBuffer timestamp:timestamp];
                }
                CVPixelBufferRelease(pixelBuffer);
            }
            break;
        }

        case VideoFrameCaptureFormat_RawData: {
            NSData *rawData = [self createRawRGBAFromFrame:frame];
            if (rawData && [self.delegate respondsToSelector:@selector(capturer:didCaptureRawData:width:height:timestamp:)]) {
                [self.delegate capturer:self didCaptureRawData:rawData width:frame.width height:frame.height timestamp:timestamp];
            }
            break;
        }

        case VideoFrameCaptureFormat_Base64: {
            NSString *base64 = [self createBase64FromFrame:frame];
            if (base64) {
                NSData *data = [base64 dataUsingEncoding:NSUTF8StringEncoding];
                if ([self.delegate respondsToSelector:@selector(capturer:didCaptureRawData:width:height:timestamp:)]) {
                    [self.delegate capturer:self didCaptureRawData:data width:frame.width height:frame.height timestamp:timestamp];
                }
            }
            break;
        }
    }

    // 通用帧回调
    if ([self.delegate respondsToSelector:@selector(capturer:didCaptureVideoFrame:)]) {
        [self.delegate capturer:self didCaptureVideoFrame:frame];
    }
}

@end
