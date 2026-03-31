//
//  VideoFrameCaptureModule.m
//  react-native-webrtc
//
//  VideoFrame 捕获模块实现
//

#import "VideoFrameCaptureModule.h"
#import "VideoFrameAdapter.h"
#import "VideoFrameCapturer.h"
#import <UIKit/UIKit.h>

@interface VideoFrameCaptureModule () <VideoFrameAdapterDelegate>

@property (nonatomic, strong) NSMutableDictionary<NSString *, VideoFrameAdapter *> *adapters;
@property (nonatomic, strong) dispatch_queue_t moduleQueue;
@property (nonatomic, assign) BOOL hasListeners;

@end

@implementation VideoFrameCaptureModule

RCT_EXPORT_MODULE();

- (instancetype)init {
    self = [super init];
    if (self) {
        _adapters = [NSMutableDictionary dictionary];
        _moduleQueue = dispatch_queue_create("com.reactnativewebrtc.capturemodule", DISPATCH_QUEUE_SERIAL);
        _hasListeners = NO;
    }
    return self;
}

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[
        @"onVideoFrame",
        @"onSnapshot",
        @"onFrameData",
        @"onCaptureError"
    ];
}

- (void)startObserving {
    self.hasListeners = YES;
}

- (void)stopObserving {
    self.hasListeners = NO;
}

#pragma mark - Public Methods

- (nullable id)getAdapterForTrackId:(NSString *)trackId {
    __block VideoFrameAdapter *adapter = nil;
    dispatch_sync(self.moduleQueue, ^{
        adapter = self.adapters[trackId];
    });
    return adapter;
}

RCT_EXPORT_METHOD(startCapture:(NSString *)trackId
                  config:(NSDictionary *)config
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            // 创建或获取适配器
            VideoFrameAdapter *adapter = self.adapters[trackId];
            if (!adapter) {
                adapter = [[VideoFrameAdapter alloc] initWithTrackId:trackId];
                adapter.delegate = self;
                self.adapters[trackId] = adapter;
            }

            // 应用配置
            if (config) {
                if (config[@"targetFrameRate"]) {
                    [adapter setTargetFrameRate:[config[@"targetFrameRate"] intValue]];
                }
                if (config[@"format"]) {
                    NSString *format = config[@"format"];
                    if ([format isEqualToString:@"pixelBuffer"]) {
                        [adapter setCaptureFormat:VideoFrameCaptureFormat_PixelBuffer];
                    } else if ([format isEqualToString:@"rawData"]) {
                        [adapter setCaptureFormat:VideoFrameCaptureFormat_RawData];
                    } else if ([format isEqualToString:@"base64"]) {
                        [adapter setCaptureFormat:VideoFrameCaptureFormat_Base64];
                    } else {
                        [adapter setCaptureFormat:VideoFrameCaptureFormat_UIImage];
                    }
                }
            }

            [adapter enableFrameCapture];

            resolve(@{
                @"success": @YES,
                @"trackId": trackId
            });

        } @catch (NSException *exception) {
            reject(@"CAPTURE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(stopCapture:(NSString *)trackId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            VideoFrameAdapter *adapter = self.adapters[trackId];
            if (adapter) {
                [adapter disableFrameCapture];
                [self.adapters removeObjectForKey:trackId];
            }

            resolve(@{
                @"success": @YES,
                @"trackId": trackId
            });

        } @catch (NSException *exception) {
            reject(@"CAPTURE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(stopAllCaptures:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            for (NSString *trackId in self.adapters) {
                VideoFrameAdapter *adapter = self.adapters[trackId];
                [adapter disableFrameCapture];
            }
            [self.adapters removeAllObjects];

            resolve(@{@"success": @YES});

        } @catch (NSException *exception) {
            reject(@"CAPTURE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(captureFrame:(NSString *)trackId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            VideoFrameAdapter *adapter = self.adapters[trackId];
            if (!adapter) {
                reject(@"NOT_FOUND", [NSString stringWithFormat:@"No active capture for track: %@", trackId], nil);
                return;
            }

            // 请求截图，结果会通过 delegate 回调
            [adapter requestSnapshot];

            // 暂时返回成功，实际结果通过事件返回
            resolve(@{
                @"success": @YES,
                @"message": @"Snapshot requested"
            });

        } @catch (NSException *exception) {
            reject(@"CAPTURE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(getFrameData:(NSString *)trackId
                  format:(NSString *)format
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            VideoFrameAdapter *adapter = self.adapters[trackId];
            if (!adapter) {
                reject(@"NOT_FOUND", [NSString stringWithFormat:@"No active capture for track: %@", trackId], nil);
                return;
            }

            VideoFrameCapturer *capturer = adapter.capturer;
            if (!capturer) {
                reject(@"CAPTURE_ERROR", @"Capturer not available", nil);
                return;
            }

            NSMutableDictionary *result = [NSMutableDictionary dictionary];
            result[@"success"] = @YES;
            result[@"message"] = @"Use onFrameData event to receive frame data";
            result[@"format"] = format;

            resolve(result);

        } @catch (NSException *exception) {
            reject(@"FRAME_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(updateConfig:(NSString *)trackId
                  config:(NSDictionary *)config
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.moduleQueue, ^{
        @try {
            VideoFrameAdapter *adapter = self.adapters[trackId];
            if (!adapter) {
                reject(@"NOT_FOUND", [NSString stringWithFormat:@"No active capture for track: %@", trackId], nil);
                return;
            }

            if (config[@"targetFrameRate"]) {
                [adapter setTargetFrameRate:[config[@"targetFrameRate"] intValue]];
            }
            if (config[@"format"]) {
                NSString *format = config[@"format"];
                if ([format isEqualToString:@"pixelBuffer"]) {
                    [adapter setCaptureFormat:VideoFrameCaptureFormat_PixelBuffer];
                } else if ([format isEqualToString:@"rawData"]) {
                    [adapter setCaptureFormat:VideoFrameCaptureFormat_RawData];
                } else if ([format isEqualToString:@"base64"]) {
                    [adapter setCaptureFormat:VideoFrameCaptureFormat_Base64];
                } else {
                    [adapter setCaptureFormat:VideoFrameCaptureFormat_UIImage];
                }
            }

            resolve(@{
                @"success": @YES,
                @"trackId": trackId
            });

        } @catch (NSException *exception) {
            reject(@"CONFIG_ERROR", exception.reason, nil);
        }
    });
}

#pragma mark - VideoFrameAdapterDelegate

- (void)adapter:(VideoFrameAdapter *)adapter didReceiveVideoFrame:(RTCVideoFrame *)frame {
    if (!self.hasListeners) return;

    VideoFrameCapturer *capturer = adapter.capturer;
    if (!capturer) return;

    NSString *base64 = [capturer createBase64FromFrame:frame];
    if (base64) {
        NSMutableDictionary *eventData = [NSMutableDictionary dictionary];
        eventData[@"trackId"] = adapter.trackId;
        eventData[@"timestamp"] = @(frame.timestampUs / 1000.0);
        eventData[@"width"] = @(frame.width);
        eventData[@"height"] = @(frame.height);
        eventData[@"data"] = base64;

        [self sendEventWithName:@"onVideoFrame" body:eventData];
    }
}

- (void)adapter:(VideoFrameAdapter *)adapter didCaptureSnapshot:(UIImage *)image timestamp:(int64_t)timestamp {
    if (!self.hasListeners) return;

    NSData *imageData = UIImageJPEGRepresentation(image, 0.9);
    NSString *base64 = [imageData base64EncodedStringWithOptions:NSDataBase64EncodingEndLineWithLineFeed];

    NSMutableDictionary *eventData = [NSMutableDictionary dictionary];
    eventData[@"trackId"] = adapter.trackId;
    eventData[@"timestamp"] = @(timestamp / 1000.0);
    eventData[@"width"] = @(image.size.width);
    eventData[@"height"] = @(image.size.height);
    eventData[@"data"] = base64;

    [self sendEventWithName:@"onSnapshot" body:eventData];
}

@end
