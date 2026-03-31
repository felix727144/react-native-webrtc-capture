//
//  VideoFrameCaptureModule.h
//  react-native-webrtc
//
//  VideoFrame 捕获模块 - React Native Bridge
//

#import <Foundation/Foundation.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

NS_ASSUME_NONNULL_BEGIN

@interface VideoFrameCaptureModule : RCTEventEmitter <RCTBridgeModule>

// 获取指定轨道的适配器
- (nullable id)getAdapterForTrackId:(NSString *)trackId;

@end

NS_ASSUME_NONNULL_END
