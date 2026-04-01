/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow strict-local
 */

import React, {useState, useRef} from 'react';
import {
  Button,
  SafeAreaView,
  StyleSheet,
  View,
  StatusBar,
  Text,
  Image,
  Alert,
  ScrollView,
} from 'react-native';
import { Colors } from 'react-native/Libraries/NewAppScreen';
import { mediaDevices, startIOSPIP, stopIOSPIP, RTCPIPView, videoFrameCapture } from 'react-native-webrtc';


const App = () => {
  const view = useRef()
  const [stream, setStream] = useState(null);
  const [capturedImage, setCapturedImage] = useState(null);
  const [videoTrackId, setVideoTrackId] = useState(null);
  const [isCapturing, setIsCapturing] = useState(false);
  const [frameData, setFrameData] = useState(null);
  const [debugLog, setDebugLog] = useState([]);

  const addLog = (msg) => {
    console.log(msg);
    setDebugLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] ${msg}`].slice(-10));
  };

  const start = async () => {
    addLog('=== Start button pressed ===');
    addLog('videoFrameCapture: ' + (videoFrameCapture ? 'exists' : 'null'));
    addLog('NativeModules keys: ' + JSON.stringify(Object.keys(require('react-native').NativeModules).filter(k => k.includes('Video') || k.includes('Capture') || k.includes('WebRTC'))));
    
    if (!stream) {
      try {
        const s = await mediaDevices.getUserMedia({ video: true });
        addLog('got user media: ' + JSON.stringify({id: s.id, active: s.active}));
        setStream(s);

        // 获取视频轨道 ID
        const videoTrack = s.getVideoTracks()[0];
        if (videoTrack) {
          const trackId = videoTrack.id;
          setVideoTrackId(trackId);
          addLog('Video track ID: ' + trackId);
          addLog('Video track object: ' + JSON.stringify({id: videoTrack.id, enabled: videoTrack.enabled, kind: videoTrack.kind}));

          // 自动开始捕获，这样 captureFrame 可以直接使用活跃的适配器
          try {
            addLog('Calling videoFrameCapture.startCapture...');
            addLog('startCapture method: ' + (typeof videoFrameCapture.startCapture));

            // 测试：直接使用 WebRTCModule
            const { NativeModules } = require('react-native');
            addLog('NativeModules.WebRTCModule:', NativeModules.WebRTCModule ? 'exists' : 'null');
            addLog('NativeModules.VideoFrameCaptureModule:', NativeModules.VideoFrameCaptureModule ? 'exists' : 'null');
            addLog('WebRTCModule methods:', Object.keys(NativeModules.WebRTCModule || {}));

            // 直接使用 NativeModules.WebRTCModule.startCapture
            addLog('Calling NativeModules.WebRTCModule.startCapture...');
            try {
              const directResult = await NativeModules.WebRTCModule.startCapture(trackId, {});
              addLog('Direct startCapture result:', JSON.stringify(directResult));
            } catch (err) {
              addLog('Direct startCapture error:', err.message);
              addLog('Direct startCapture error stack:', err.stack);
            }

            // 直接使用 NativeModules.VideoFrameCaptureModule.captureFrame 测试
            addLog('Testing NativeModules.VideoFrameCaptureModule.captureFrame...');
            try {
              if (NativeModules.VideoFrameCaptureModule) {
                const vfcResult = await NativeModules.VideoFrameCaptureModule.captureFrame(trackId);
                addLog('Direct VideoFrameCaptureModule result:', JSON.stringify(vfcResult));
              } else {
                addLog('VideoFrameCaptureModule not found in NativeModules');
              }
            } catch (err) {
              addLog('Direct VideoFrameCaptureModule error:', err.message);
              addLog('Direct VideoFrameCaptureModule error stack:', err.stack);
            }

            const result = await videoFrameCapture.startCapture(trackId, {
              targetFrameRate: 0, // 不主动捕获帧，只保持适配器活跃
              format: 'image',
            });
            addLog('Auto-started capture: ' + JSON.stringify(result));
          } catch (err) {
            addLog('Failed to auto-start capture: ' + err.message);
            addLog('Error stack: ' + err.stack);
          }
        } else {
          addLog('No video track found in stream');
        }
      } catch(e) {
        addLog('Error: ' + e.message);
        console.error(e);
      }
    }
  };

  const startPIP = () => {
    startIOSPIP(view);
  };

  const stopPIP = () => {
    stopIOSPIP(view);
  };

  const stop = () => {
    console.log('stop');
    if (stream) {
      stream.release();
      setStream(null);
      setVideoTrackId(null);
      setCapturedImage(null);
      setFrameData(null);
      setIsCapturing(false);
    }
  };

  /**
   * 测试 captureFrame - 截图功能
   */
  const testCaptureFrame = async () => {
    addLog('=== testCaptureFrame called ===');

    if (!videoTrackId) {
      addLog('No video track ID');
      Alert.alert('错误', '请先启动摄像头');
      return;
    }

    addLog('Video track ID: ' + videoTrackId);
    addLog('videoFrameCapture: ' + (videoFrameCapture ? 'exists' : 'null'));

    try {
      addLog('Capturing frame for track: ' + videoTrackId);

      // 直接使用 NativeModules.WebRTCModule.captureFrame
      const { NativeModules } = require('react-native');
      
      // 强制访问 WebRTCModule 以触发懒加载
      const wm = NativeModules.WebRTCModule;
      addLog('wm type: ' + typeof wm);
      addLog('wm keys: ' + (wm ? Object.keys(wm).join(', ') : 'null'));
      addLog('wm.captureFrame type: ' + typeof wm?.captureFrame);
      
      let result;
      addLog('About to call wm.captureFrame...');
      try {
        result = await wm.captureFrame(videoTrackId);
        addLog('Direct captureFrame result: success=' + result.success + ', ' + result.width + 'x' + result.height);
      } catch (directError) {
        addLog('Direct captureFrame error: ' + directError.message);
        addLog('Direct captureFrame error stack: ' + directError.stack);
      }
      addLog('Direct captureFrame call completed');

      // 尝试多次，因为轨道可能还没有准备好
      let retries = 3;
      while (retries > 0) {
        try {
          addLog('Attempt ' + (4 - retries) + '...');
          result = await videoFrameCapture.captureFrame(videoTrackId);
          addLog('Capture result: success=' + result.success + ', ' + result.width + 'x' + result.height);
          break;
        } catch (error) {
          addLog('Capture attempt failed: ' + error.message);
          retries--;
          if (retries === 0) throw error;
          await new Promise(resolve => setTimeout(resolve, 500));
        }
      }
      

      if (result && result.success) {
        setCapturedImage(`data:image/jpeg;base64,${result.data}`);
        setFrameData(`分辨率：${result.width}x${result.height}\n时间戳：${result.timestamp}ms`);
        addLog('Screenshot success: ' + result.width + 'x' + result.height);
        Alert.alert('截图成功', `分辨率：${result.width}x${result.height}`);
      } else {
        addLog('Screenshot failed: ' + (result?.error || 'unknown'));
        Alert.alert('截图失败', result?.error || '未知错误');
      }
    } catch (error) {
      addLog('Capture frame error: ' + error.message);
      console.error('Capture frame error:', error);
      Alert.alert('截图失败', error.message || '发生错误');
    }
  };

  /**
   * 测试 startCapture - 开始实时帧捕获
   */
  const testStartCapture = async () => {
    if (!videoTrackId) {
      Alert.alert('错误', '请先启动摄像头');
      return;
    }

    try {
      console.log('Starting capture for track:', videoTrackId);
      const result = await videoFrameCapture.startCapture(videoTrackId, {
        targetFrameRate: 1, // 每秒 1 帧
        format: 'image',
        quality: 80,
      });
      console.log('Start capture result:', result);
      setIsCapturing(true);
      Alert.alert('开始捕获', '已开始实时帧捕获，每秒 1 帧');

      // 注册帧监听器
      videoFrameCapture.onFrame(videoTrackId, (frame) => {
        console.log('Received frame:', frame.width, 'x', frame.height);
        setFrameData(`实时帧：${frame.width}x${frame.height}\n时间戳：${frame.timestamp}ms`);
      });

    } catch (error) {
      console.error('Start capture error:', error);
      Alert.alert('启动捕获失败', error.message || '发生错误');
    }
  };

  /**
   * 测试 stopCapture - 停止实时帧捕获
   */
  const testStopCapture = async () => {
    if (!videoTrackId) {
      Alert.alert('错误', '请先启动摄像头');
      return;
    }

    try {
      console.log('Stopping capture for track:', videoTrackId);
      const result = await videoFrameCapture.stopCapture(videoTrackId);
      console.log('Stop capture result:', result);
      setIsCapturing(false);
      setFrameData(null);
      Alert.alert('停止捕获', '已停止实时帧捕获');
    } catch (error) {
      console.error('Stop capture error:', error);
      Alert.alert('停止捕获失败', error.message || '发生错误');
    }
  };

  /**
   * 测试 getFrameData - 获取原始帧数据
   */
  const testGetFrameData = async () => {
    if (!videoTrackId) {
      Alert.alert('错误', '请先启动摄像头');
      return;
    }

    try {
      console.log('Getting frame data for track:', videoTrackId);
      const result = await videoFrameCapture.getFrameData(videoTrackId, 'bitmap');
      console.log('Frame data result:', result);
      
      if (result.data) {
        setCapturedImage(`data:image/png;base64,${result.data}`);
        setFrameData(`原始数据格式：${result.format}\n分辨率：${result.width}x${result.height}`);
        Alert.alert('获取成功', `格式：${result.format}\n分辨率：${result.width}x${result.height}`);
      }
    } catch (error) {
      console.error('Get frame data error:', error);
      Alert.alert('获取失败', error.message || '发生错误');
    }
  };

  let pipOptions = {
    startAutomatically: true,
    fallbackView: (<View style={{ height: 50, width: 50, backgroundColor: 'red' }} />),
    preferredSize: {
      width: 400,
      height: 800,
    }
  }

  return (
    <>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.body}>
      {
        stream &&
        <RTCPIPView
            ref={view}
            streamURL={stream.toURL()}
            style={styles.stream}
            iosPIP={pipOptions} >
        </RTCPIPView>
      }

      {/* 截图显示区域 */}
      {capturedImage && (
        <View style={styles.imageContainer}>
          <Text style={styles.imageTitle}>捕获的图像:</Text>
          <Image 
            source={{ uri: capturedImage }} 
            style={styles.capturedImage}
            resizeMode="contain"
          />
          {frameData && <Text style={styles.frameData}>{frameData}</Text>}
        </View>
      )}
      
      {/* 调试日志显示 */}
      <View style={styles.debugContainer}>
        <Text style={styles.debugTitle}>调试日志:</Text>
        <ScrollView style={styles.debugLog}>
          {debugLog.map((log, i) => (
            <Text key={i} style={styles.debugText}>{log}</Text>
          ))}
        </ScrollView>
      </View>
      
      <ScrollView style={styles.buttonContainer}>
        <Text style={styles.sectionTitle}>基础控制</Text>
        <Button
          title = "Start"
          onPress = {start} />
        <Button
          title = "Start PIP"
          onPress = {startPIP} />
        <Button
          title = "Stop PIP"
          onPress = {stopPIP} />
        <Button
          title = "Stop"
          onPress = {stop} />
        
        <Text style={styles.sectionTitle}>VideoFrame 捕获测试</Text>
        <Text style={styles.infoText}>
          当前轨道 ID: {videoTrackId || '未启动'}
        </Text>
        <Text style={styles.infoText}>
          捕获状态：{isCapturing ? '正在捕获' : '未捕获'}
        </Text>
        
        <Button
          title = "📸 截图 (captureFrame)"
          onPress = {testCaptureFrame}
          disabled={!videoTrackId} />
        
        <Button
          title = {isCapturing ? "⏹️ 停止捕获 (stopCapture)" : "▶️ 开始捕获 (startCapture)"}
          onPress = {isCapturing ? testStopCapture : testStartCapture}
          disabled={!videoTrackId} />
        
        <Button
          title = "🔍 获取原始数据 (getFrameData)"
          onPress = {testGetFrameData}
          disabled={!videoTrackId} />
      </ScrollView>
      </SafeAreaView>
    </>
  );
};

const styles = StyleSheet.create({
  body: {
    backgroundColor: Colors.white,
    ...StyleSheet.absoluteFill
  },
  stream: {
    flex: 1
  },
  footer: {
    backgroundColor: Colors.lighter,
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0
  },
  buttonContainer: {
    padding: 10,
    backgroundColor: Colors.lighter,
    maxHeight: 300,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginTop: 10,
    marginBottom: 5,
  },
  infoText: {
    fontSize: 12,
    color: Colors.dark,
    marginBottom: 5,
  },
  imageContainer: {
    padding: 10,
    backgroundColor: '#f0f0f0',
    borderBottomWidth: 1,
    borderBottomColor: '#ccc',
  },
  imageTitle: {
    fontSize: 14,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  capturedImage: {
    width: '100%',
    height: 200,
    backgroundColor: '#000',
  },
  frameData: {
    fontSize: 12,
    color: '#666',
    marginTop: 5,
  },
  debugContainer: {
    padding: 10,
    backgroundColor: '#fff3cd',
    borderBottomWidth: 1,
    borderBottomColor: '#ccc',
    maxHeight: 150,
  },
  debugTitle: {
    fontSize: 12,
    fontWeight: 'bold',
    marginBottom: 5,
  },
  debugLog: {
    maxHeight: 100,
  },
  debugText: {
    fontSize: 10,
    color: '#333',
    fontFamily: 'monospace',
  },
});

export default App;
