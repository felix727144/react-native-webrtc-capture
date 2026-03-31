//
//  VideoFrameAdapter.h
//  react-native-webrtc
//
//  VideoFrame 适配器 - Hook WebRTC 视频帧
//

#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>
#import "VideoFrameCapturer.h"

NS_ASSUME_NONNULL_BEGIN

@class VideoFrameAdapter;

@protocol VideoFrameAdapterDelegate <NSObject>
@optional
- (void)adapter:(VideoFrameAdapter *)adapter didReceiveVideoFrame:(RTCVideoFrame *)frame;
- (void)adapter:(VideoFrameAdapter *)adapter didCaptureSnapshot:(UIImage *)image timestamp:(int64_t)timestamp;
@end

@interface VideoFrameAdapter : NSObject <RTCVideoFrameAdapterDelegate>

@property (nonatomic, weak, nullable) id<VideoFrameAdapterDelegate> delegate;
@property (nonatomic, strong, readonly) NSString *trackId;
@property (nonatomic, assign) BOOL frameCaptureEnabled;
@property (nonatomic, strong, nullable) VideoFrameCapturer *capturer;

// 原始渲染器
@property (nonatomic, strong, nullable) id<RTCVideoRenderer> originalRenderer;

- (instancetype)initWithTrackId:(NSString *)trackId;

// 帧捕获控制
- (void)enableFrameCapture;
- (void)disableFrameCapture;
- (void)requestSnapshot;

// 配置
- (void)setTargetFrameRate:(int)frameRate;
- (void)setCaptureFormat:(VideoFrameCaptureFormat)format;

@end

NS_ASSUME_NONNULL_END
