//
//  VideoFrameCapturer.h
//  react-native-webrtc
//
//  VideoFrame 捕获器 - 用于将 WebRTC RTCVideoFrame 转换为 UIImage/CVPixelBuffer
//

#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>
#import <CoreVideo/CoreVideo.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@class VideoFrameCapturer;

@protocol VideoFrameCapturerDelegate <NSObject>
@optional
- (void)capturer:(VideoFrameCapturer *)capturer didCaptureVideoFrame:(RTCVideoFrame *)frame;
- (void)capturer:(VideoFrameCapturer *)capturer didCaptureImage:(UIImage *)image timestamp:(int64_t)timestamp;
- (void)capturer:(VideoFrameCapturer *)capturer didCapturePixelBuffer:(CVPixelBufferRef)pixelBuffer timestamp:(int64_t)timestamp;
- (void)capturer:(VideoFrameCapturer *)capturer didCaptureRawData:(NSData *)rawData width:(int)width height:(int)height timestamp:(int64_t)timestamp;
- (void)capturer:(VideoFrameCapturer *)capturer didFailWithError:(NSError *)error;
@end

typedef NS_ENUM(NSInteger, VideoFrameCaptureFormat) {
    VideoFrameCaptureFormat_UIImage,
    VideoFrameCaptureFormat_PixelBuffer,
    VideoFrameCaptureFormat_RawData,
    VideoFrameCaptureFormat_Base64
};

@interface VideoFrameCapturer : NSObject

@property (nonatomic, weak, nullable) id<VideoFrameCapturerDelegate> delegate;
@property (nonatomic, assign) BOOL captureEnabled;
@property (nonatomic, assign) VideoFrameCaptureFormat captureFormat;
@property (nonatomic, assign) int targetFrameRate;
@property (nonatomic, strong, nullable) NSString *trackId;

- (instancetype)initWithTrackId:(NSString *)trackId;
- (void)startCapturing;
- (void)stopCapturing;

// 截图方法
- (nullable UIImage *)captureSnapshot;
- (void)captureSnapshotWithCompletion:(void (^)(UIImage * _Nullable image, NSError * _Nullable error))completion;

// 数据转换方法
- (nullable CVPixelBufferRef)createPixelBufferFromFrame:(RTCVideoFrame *)frame CF_RETURNS_RETAINED;
- (nullable UIImage *)createImageFromFrame:(RTCVideoFrame *)frame;
- (nullable NSData *)createRawRGBAFromFrame:(RTCVideoFrame *)frame;
- (nullable NSString *)createBase64FromFrame:(RTCVideoFrame *)frame;

// YUV to RGB 转换
- (void)convertYUVToRGB:(const uint8_t *)yPlane
                 uPlane:(const uint8_t *)uPlane
                 vPlane:(const uint8_t *)vPlane
              yStride:(int)yStride
              uStride:(int)uStride
              vStride:(int)vStride
                width:(int)width
               height:(int)height
            outputBuffer:(uint8_t *)outputBuffer;

@end

NS_ASSUME_NONNULL_END
