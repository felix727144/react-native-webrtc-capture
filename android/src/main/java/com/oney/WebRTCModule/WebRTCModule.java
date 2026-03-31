package com.oney.WebRTCModule;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.oney.WebRTCModule.VideoFrameHook.VideoFrameCaptureModule;
import com.oney.WebRTCModule.webrtcutils.H264AndSoftwareVideoDecoderFactory;
import com.oney.WebRTCModule.webrtcutils.H264AndSoftwareVideoEncoderFactory;

import org.webrtc.*;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

@ReactModule(name = "WebRTCModule")
public class WebRTCModule extends ReactContextBaseJavaModule {
    static final String TAG = WebRTCModule.class.getCanonicalName();
    private static WebRTCModule instance;

    PeerConnectionFactory mFactory;
    VideoEncoderFactory mVideoEncoderFactory;
    VideoDecoderFactory mVideoDecoderFactory;
    AudioDeviceModule mAudioDeviceModule;

    // Need to expose the peer connection codec factories here to get capabilities
    private final SparseArray<PeerConnectionObserver> mPeerConnectionObservers;
    final Map<String, MediaStream> localStreams;

    private final GetUserMediaImpl getUserMediaImpl;

    public WebRTCModule(ReactApplicationContext reactContext) {
        super(reactContext);
        instance = this;

        mPeerConnectionObservers = new SparseArray<>();
        localStreams = new HashMap<>();

        WebRTCModuleOptions options = WebRTCModuleOptions.getInstance();

        AudioDeviceModule adm = options.audioDeviceModule;
        VideoEncoderFactory encoderFactory = options.videoEncoderFactory;
        VideoDecoderFactory decoderFactory = options.videoDecoderFactory;
        Loggable injectableLogger = options.injectableLogger;
        Logging.Severity loggingSeverity = options.loggingSeverity;
        String fieldTrials = options.fieldTrials;

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(reactContext)
                        .setFieldTrials(fieldTrials)
                        .setNativeLibraryLoader(new LibraryLoader())
                        .setInjectableLogger(injectableLogger, loggingSeverity)
                        .createInitializationOptions());

        if (injectableLogger == null && loggingSeverity != null) {
            Logging.enableLogToDebugOutput(loggingSeverity);
        }

        if (encoderFactory == null || decoderFactory == null) {
            // Initialize EGL context required for HW acceleration.
            EglBase.Context eglContext = EglUtils.getRootEglBaseContext();

            if (eglContext != null) {
                encoderFactory = new H264AndSoftwareVideoEncoderFactory(eglContext);
                decoderFactory = new H264AndSoftwareVideoDecoderFactory(eglContext);
            } else {
                encoderFactory = new SoftwareVideoEncoderFactory();
                decoderFactory = new SoftwareVideoDecoderFactory();
            }
        }

        if (adm == null) {
            adm = JavaAudioDeviceModule.builder(reactContext).setEnableVolumeLogger(false).createAudioDeviceModule();
        }

        Log.d(TAG, "Using video encoder factory: " + encoderFactory.getClass().getCanonicalName());
        Log.d(TAG, "Using video decoder factory: " + decoderFactory.getClass().getCanonicalName());

        mFactory = PeerConnectionFactory.builder()
                           .setAudioDeviceModule(adm)
                           .setVideoEncoderFactory(encoderFactory)
                           .setVideoDecoderFactory(decoderFactory)
                           .createPeerConnectionFactory();

        // PeerConnectionFactory now owns the adm native pointer, and we don't need it anymore.
        adm.release();

        // Saving the encoder and decoder factories to get codec info later when needed.
        mVideoEncoderFactory = encoderFactory;
        mVideoDecoderFactory = decoderFactory;
        mAudioDeviceModule = adm;

        getUserMediaImpl = new GetUserMediaImpl(this, reactContext);
    }

    @NonNull
    @Override
    public String getName() {
        return "WebRTCModule";
    }

    public static WebRTCModule getInstance() {
        return instance;
    }

    public Map<String, MediaStream> getLocalStreams() {
        return localStreams;
    }

    private PeerConnection getPeerConnection(int id) {
        PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
        return (pco == null) ? null : pco.getPeerConnection();
    }

    void sendEvent(String eventName, @Nullable ReadableMap params) {
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    private PeerConnection.IceServer createIceServer(String url) {
        return PeerConnection.IceServer.builder(url).createIceServer();
    }

    private PeerConnection.IceServer createIceServer(String url, String username, String credential) {
        return PeerConnection.IceServer.builder(url).setUsername(username).setPassword(credential).createIceServer();
    }

    private List<PeerConnection.IceServer> createIceServers(ReadableArray iceServersArray) {
        final int size = (iceServersArray == null) ? 0 : iceServersArray.size();
        List<PeerConnection.IceServer> iceServers = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ReadableMap iceServerMap = iceServersArray.getMap(i);
            boolean hasUsernameAndCredential = iceServerMap.hasKey("username") && iceServerMap.hasKey("credential");
            if (iceServerMap.hasKey("urls")) {
                switch (iceServerMap.getType("urls")) {
                    case String:
                        if (hasUsernameAndCredential) {
                            iceServers.add(createIceServer(iceServerMap.getString("urls"),
                                    iceServerMap.getString("username"),
                                    iceServerMap.getString("credential")));
                        } else {
                            iceServers.add(createIceServer(iceServerMap.getString("urls")));
                        }
                        break;
                    case Array:
                        ReadableArray urls = iceServerMap.getArray("urls");
                        for (int j = 0; j < urls.size(); j++) {
                            String url = urls.getString(j);
                            if (hasUsernameAndCredential) {
                                iceServers.add(createIceServer(
                                        url, iceServerMap.getString("username"), iceServerMap.getString("credential")));
                            } else {
                                iceServers.add(createIceServer(url));
                            }
                        }
                        break;
                }
            }
        }
        return iceServers;
    }

    private PeerConnection.RTCConfiguration parseRTCConfiguration(ReadableMap map) {
        ReadableArray iceServersArray = null;
        if (map != null && map.hasKey("iceServers")) {
            iceServersArray = map.getArray("iceServers");
        }
        List<PeerConnection.IceServer> iceServers = createIceServers(iceServersArray);

        PeerConnection.RTCConfiguration conf = new PeerConnection.RTCConfiguration(iceServers);
        conf.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // Required for perfect negotiation.
        conf.enableImplicitRollback = true;

        // Enable GCM ciphers.
        CryptoOptions cryptoOptions = CryptoOptions.builder()
                                              .setEnableGcmCryptoSuites(true)
                                              .setEnableAes128Sha1_32CryptoCipher(false)
                                              .setEnableEncryptedRtpHeaderExtensions(false)
                                              .setRequireFrameEncryption(false)
                                              .createCryptoOptions();
        conf.cryptoOptions = cryptoOptions;

        if (map == null) {
            return conf;
        }

        // iceTransportPolicy (public api)
        if (map.hasKey("iceTransportPolicy") && map.getType("iceTransportPolicy") == ReadableType.String) {
            final String v = map.getString("iceTransportPolicy");
            if (v != null) {
                switch (v) {
                    case "all": // public
                        conf.iceTransportsType = PeerConnection.IceTransportsType.ALL;
                        break;
                    case "relay": // public
                        conf.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
                        break;
                    case "nohost":
                        conf.iceTransportsType = PeerConnection.IceTransportsType.NOHOST;
                        break;
                    case "none":
                        conf.iceTransportsType = PeerConnection.IceTransportsType.NONE;
                        break;
                }
            }
        }

        // bundlePolicy (public api)
        if (map.hasKey("bundlePolicy") && map.getType("bundlePolicy") == ReadableType.String) {
            final String v = map.getString("bundlePolicy");
            if (v != null) {
                switch (v) {
                    case "balanced": // public
                        conf.bundlePolicy = PeerConnection.BundlePolicy.BALANCED;
                        break;
                    case "max-compat": // public
                        conf.bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT;
                        break;
                    case "max-bundle": // public
                        conf.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
                        break;
                }
            }
        }

        // rtcpMuxPolicy (public api)
        if (map.hasKey("rtcpMuxPolicy") && map.getType("rtcpMuxPolicy") == ReadableType.String) {
            final String v = map.getString("rtcpMuxPolicy");
            if (v != null) {
                switch (v) {
                    case "negotiate": // public
                        conf.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
                        break;
                    case "require": // public
                        conf.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
                        break;
                }
            }
        }

        // FIXME: peerIdentity of type DOMString (public api)
        // FIXME: certificates of type sequence<RTCCertificate> (public api)

        // iceCandidatePoolSize of type unsigned short, defaulting to 0
        if (map.hasKey("iceCandidatePoolSize") && map.getType("iceCandidatePoolSize") == ReadableType.Number) {
            final int v = map.getInt("iceCandidatePoolSize");
            if (v > 0) {
                conf.iceCandidatePoolSize = v;
            }
        }

        // === below is private api in webrtc ===

        // tcpCandidatePolicy (private api)
        if (map.hasKey("tcpCandidatePolicy") && map.getType("tcpCandidatePolicy") == ReadableType.String) {
            final String v = map.getString("tcpCandidatePolicy");
            if (v != null) {
                switch (v) {
                    case "enabled":
                        conf.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
                        break;
                    case "disabled":
                        conf.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
                        break;
                }
            }
        }

        // candidateNetworkPolicy (private api)
        if (map.hasKey("candidateNetworkPolicy") && map.getType("candidateNetworkPolicy") == ReadableType.String) {
            final String v = map.getString("candidateNetworkPolicy");
            if (v != null) {
                switch (v) {
                    case "all":
                        conf.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
                        break;
                    case "low_cost":
                        conf.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST;
                        break;
                }
            }
        }

        // KeyType (private api)
        if (map.hasKey("keyType") && map.getType("keyType") == ReadableType.String) {
            final String v = map.getString("keyType");
            if (v != null) {
                switch (v) {
                    case "RSA":
                        conf.keyType = PeerConnection.KeyType.RSA;
                        break;
                    case "ECDSA":
                        conf.keyType = PeerConnection.KeyType.ECDSA;
                        break;
                }
            }
        }

        // continualGatheringPolicy (private api)
        if (map.hasKey("continualGatheringPolicy") && map.getType("continualGatheringPolicy") == ReadableType.String) {
            final String v = map.getString("continualGatheringPolicy");
            if (v != null) {
                switch (v) {
                    case "gather_once":
                        conf.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
                        break;
                    case "gather_continually":
                        conf.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
                        break;
                }
            }
        }

        // audioJitterBufferMaxPackets (private api)
        if (map.hasKey("audioJitterBufferMaxPackets")
                && map.getType("audioJitterBufferMaxPackets") == ReadableType.Number) {
            final int v = map.getInt("audioJitterBufferMaxPackets");
            if (v > 0) {
                conf.audioJitterBufferMaxPackets = v;
            }
        }

        // iceConnectionReceivingTimeout (private api)
        if (map.hasKey("iceConnectionReceivingTimeout")
                && map.getType("iceConnectionReceivingTimeout") == ReadableType.Number) {
            final int v = map.getInt("iceConnectionReceivingTimeout");
            conf.iceConnectionReceivingTimeout = v;
        }

        // iceBackupCandidatePairPingInterval (private api)
        if (map.hasKey("iceBackupCandidatePairPingInterval")
                && map.getType("iceBackupCandidatePairPingInterval") == ReadableType.Number) {
            final int v = map.getInt("iceBackupCandidatePairPingInterval");
            conf.iceBackupCandidatePairPingInterval = v;
        }

        // audioJitterBufferFastAccelerate (private api)
        if (map.hasKey("audioJitterBufferFastAccelerate")
                && map.getType("audioJitterBufferFastAccelerate") == ReadableType.Boolean) {
            final boolean v = map.getBoolean("audioJitterBufferFastAccelerate");
            conf.audioJitterBufferFastAccelerate = v;
        }

        // pruneTurnPorts (private api)
        if (map.hasKey("pruneTurnPorts") && map.getType("pruneTurnPorts") == ReadableType.Boolean) {
            final boolean v = map.getBoolean("pruneTurnPorts");
            conf.pruneTurnPorts = v;
        }

        // presumeWritableWhenFullyRelayed (private api)
        if (map.hasKey("presumeWritableWhenFullyRelayed")
                && map.getType("presumeWritableWhenFullyRelayed") == ReadableType.Boolean) {
            final boolean v = map.getBoolean("presumeWritableWhenFullyRelayed");
            conf.presumeWritableWhenFullyRelayed = v;
        }

        return conf;
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public boolean peerConnectionInit(ReadableMap configuration, int id) {
        PeerConnection.RTCConfiguration rtcConfiguration = parseRTCConfiguration(configuration);

        try {
            return (boolean) ThreadUtils
                    .submitToExecutor(() -> {
                        PeerConnectionObserver observer = new PeerConnectionObserver(this, id);
                        PeerConnection peerConnection = mFactory.createPeerConnection(rtcConfiguration, observer);
                        if (peerConnection == null) {
                            return false;
                        }
                        observer.setPeerConnection(peerConnection);
                        mPeerConnectionObservers.put(id, observer);
                        return true;
                    })
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    // Must be called in the executor.
    MediaStream getStreamForReactTag(String streamReactTag) {
        MediaStream stream = localStreams.get(streamReactTag);

        if (stream != null) {
            return stream;
        }

        for (int i = 0, size = mPeerConnectionObservers.size(); i < size; i++) {
            PeerConnectionObserver pco = mPeerConnectionObservers.valueAt(i);
            stream = pco.remoteStreams.get(streamReactTag);
            if (stream != null) {
                return stream;
            }
        }

        return null;
    }

    public MediaStreamTrack getTrack(int pcId, String trackId) {
        if (pcId == -1) {
            return getLocalTrack(trackId);
        }

        PeerConnectionObserver pco = mPeerConnectionObservers.get(pcId);
        if (pco == null) {
            Log.d(TAG, "getTrack(): could not find PeerConnection");
            return null;
        }

        return pco.remoteTracks.get(trackId);
    }

    MediaStreamTrack getLocalTrack(String trackId) {
        return getUserMediaImpl.getTrack(trackId);
    }

    public VideoTrack createVideoTrack(AbstractVideoCaptureController videoCaptureController) {
        return getUserMediaImpl.createVideoTrack(videoCaptureController);
    }

    public void createStream(
            MediaStreamTrack[] tracks, GetUserMediaImpl.BiConsumer<String, ArrayList<WritableMap>> successCallback) {
        getUserMediaImpl.createStream(tracks, successCallback);
    }

    /**
     * Turns an "options" <tt>ReadableMap</tt> into a <tt>MediaConstraints</tt> object.
     *
     * @param options A <tt>ReadableMap</tt> which represents a JavaScript
     * object specifying the options to be parsed into a
     * <tt>MediaConstraints</tt> instance.
     * @return A new <tt>MediaConstraints</tt> instance initialized with the
     * mandatory keys and values specified by <tt>options</tt>.
     */
    MediaConstraints constraintsForOptions(ReadableMap options) {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ReadableMapKeySetIterator keyIterator = options.keySetIterator();

        while (keyIterator.hasNextKey()) {
            String key = keyIterator.nextKey();
            String value = ReactBridgeUtil.getMapStrValue(options, key);

            mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(key, value));
        }

        return mediaConstraints;
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap peerConnectionAddTransceiver(int id, ReadableMap options) {
        try {
            return (WritableMap) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                        if (pco == null) {
                            Log.d(TAG, "peerConnectionAddTransceiver() peerConnection is null");
                            return null;
                        }

                        RtpTransceiver transceiver = null;
                        if (options.hasKey("type")) {
                            String kind = options.getString("type");
                            transceiver = pco.addTransceiver(SerializeUtils.parseMediaType(kind),
                                    SerializeUtils.parseTransceiverOptions(options.getMap("init")));
                        } else if (options.hasKey("trackId")) {
                            String trackId = options.getString("trackId");
                            MediaStreamTrack track = getLocalTrack(trackId);
                            transceiver = pco.addTransceiver(
                                    track, SerializeUtils.parseTransceiverOptions(options.getMap("init")));

                        } else {
                            // This should technically never happen as the JS side checks for that.
                            Log.d(TAG, "peerConnectionAddTransceiver() no type nor trackId provided in options");
                            return null;
                        }

                        if (transceiver == null) {
                            Log.d(TAG, "peerConnectionAddTransceiver() Error adding transceiver");
                            return null;
                        }
                        WritableMap params = Arguments.createMap();
                        // We need to get a unique order at which the transceiver was created
                        // to reorder the cached array of transceivers on the JS layer.
                        params.putInt("transceiverOrder", pco.getNextTransceiverId());
                        params.putMap("transceiver", SerializeUtils.serializeTransceiver(id, transceiver));
                        return params;
                    })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            Log.d(TAG, "peerConnectionAddTransceiver() " + e.getMessage());
            return null;
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap peerConnectionAddTrack(int id, String trackId, ReadableMap options) {
        try {
            return (WritableMap) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                        if (pco == null) {
                            Log.d(TAG, "peerConnectionAddTrack() peerConnection is null");
                            return null;
                        }

                        MediaStreamTrack track = getLocalTrack(trackId);
                        if (track == null) {
                            Log.w(TAG, "peerConnectionAddTrack() couldn't find track " + trackId);
                            return null;
                        }

                        List<String> streamIds = new ArrayList<>();
                        if (options.hasKey("streamIds")) {
                            ReadableArray rawStreamIds = options.getArray("streamIds");
                            if (rawStreamIds != null) {
                                for (int i = 0; i < rawStreamIds.size(); i++) {
                                    streamIds.add(rawStreamIds.getString(i));
                                }
                            }
                        }
                        RtpSender sender = pco.getPeerConnection().addTrack(track, streamIds);

                        // Need to get the corresponding transceiver as well
                        RtpTransceiver transceiver = pco.getTransceiver(sender.id());

                        // We need the transceiver creation order to reorder the transceivers array
                        // in the JS layer.
                        WritableMap params = Arguments.createMap();
                        params.putInt("transceiverOrder", pco.getNextTransceiverId());
                        params.putMap("transceiver", SerializeUtils.serializeTransceiver(id, transceiver));
                        params.putMap("sender", SerializeUtils.serializeSender(id, sender));
                        return params;
                    })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            Log.d(TAG, "peerConnectionAddTrack() " + e.getMessage());
            return null;
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public boolean peerConnectionRemoveTrack(int id, String senderId) {
        try {
            return (boolean) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                        if (pco == null) {
                            Log.d(TAG, "peerConnectionRemoveTrack() peerConnection is null");
                            return false;
                        }
                        RtpSender sender = pco.getSender(senderId);
                        if (sender == null) {
                            Log.w(TAG, "peerConnectionRemoveTrack() sender is null");
                            return false;
                        }

                        return pco.getPeerConnection().removeTrack(sender);
                    })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            Log.d(TAG, "peerConnectionRemoveTrack() " + e.getMessage());
            return false;
        }
    }

    @ReactMethod
    public void senderSetParameters(int id, String senderId, ReadableMap options, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            try {
                PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                if (pco == null) {
                    Log.d(TAG, "senderSetParameters() peerConnectionObserver is null");
                    promise.reject(new Exception("Peer Connection is not initialized"));
                    return;
                }

                RtpSender sender = pco.getSender(senderId);
                if (sender == null) {
                    Log.w(TAG, "senderSetParameters() sender is null");
                    promise.reject(new Exception("Could not get sender"));
                    return;
                }

                RtpParameters params = sender.getParameters();
                params = SerializeUtils.updateRtpParameters(options, params);
                sender.setParameters(params);
                promise.resolve(SerializeUtils.serializeRtpParameters(sender.getParameters()));
            } catch (Exception e) {
                Log.d(TAG, "senderSetParameters: " + e.getMessage());
                promise.reject(e);
            }
        });
    }

    @ReactMethod
    public void transceiverStop(int id, String senderId, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            try {
                PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                if (pco == null) {
                    Log.d(TAG, "transceiverStop() peerConnectionObserver is null");
                    promise.reject(new Exception("Peer Connection is not initialized"));
                    return;
                }
                RtpTransceiver transceiver = pco.getTransceiver(senderId);
                if (transceiver == null) {
                    Log.w(TAG, "transceiverStop() transceiver is null");
                    promise.reject(new Exception("Could not get transceiver"));
                    return;
                }

                transceiver.stopStandard();
                promise.resolve(true);
            } catch (Exception e) {
                Log.d(TAG, "transceiverStop(): " + e.getMessage());
                promise.reject(e);
            }
        });
    }

    @ReactMethod
    public void senderReplaceTrack(int id, String senderId, String trackId, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            try {
                PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                if (pco == null) {
                    Log.d(TAG, "senderReplaceTrack() peerConnectionObserver is null");
                    promise.reject(new Exception("Peer Connection is not initialized"));
                    return;
                }

                RtpSender sender = pco.getSender(senderId);
                if (sender == null) {
                    Log.w(TAG, "senderReplaceTrack() sender is null");
                    promise.reject(new Exception("Could not get sender"));
                    return;
                }

                MediaStreamTrack track = getLocalTrack(trackId);
                sender.setTrack(track, false);
                promise.resolve(true);
            } catch (Exception e) {
                Log.d(TAG, "senderReplaceTrack(): " + e.getMessage());
                promise.reject(e);
            }
        });
    }

    @ReactMethod
    public void transceiverSetDirection(int id, String senderId, String direction, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            WritableMap identifier = Arguments.createMap();
            WritableMap params = Arguments.createMap();
            identifier.putInt("peerConnectionId", id);
            identifier.putString("transceiverId", senderId);
            try {
                PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                if (pco == null) {
                    Log.d(TAG, "transceiverSetDirection() peerConnectionObserver is null");
                    promise.reject(new Exception("Peer Connection is not initialized"));
                    return;
                }
                RtpTransceiver transceiver = pco.getTransceiver(senderId);
                if (transceiver == null) {
                    Log.d(TAG, "transceiverSetDirection() transceiver is null");
                    promise.reject(new Exception("Could not get sender"));
                    return;
                }

                transceiver.setDirection(SerializeUtils.parseDirection(direction));

                promise.resolve(true);
            } catch (Exception e) {
                Log.d(TAG, "transceiverSetDirection(): " + e.getMessage());
                promise.reject(e);
            }
        });
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public boolean transceiverSetCodecPreferences(int id, String senderId, ReadableArray codecPreferences) {
        ThreadUtils.runOnExecutor(() -> {
            WritableMap identifier = Arguments.createMap();
            WritableMap params = Arguments.createMap();
            identifier.putInt("peerConnectionId", id);
            identifier.putString("transceiverId", senderId);
            try {
                PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
                if (pco == null) {
                    Log.d(TAG, "transceiverSetDirection() peerConnectionObserver is null");
                    return;
                }
                RtpTransceiver transceiver = pco.getTransceiver(senderId);
                if (transceiver == null) {
                    Log.d(TAG, "transceiverSetDirection() transceiver is null");
                    return;
                }

                // Convert JSON codec capabilities to the actual objects.
                RtpTransceiver.RtpTransceiverDirection direction = transceiver.getDirection();
                List<Pair<Map<String, Object>, RtpCapabilities.CodecCapability>> availableCodecs = new ArrayList<>();

                if (direction.equals(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        || direction.equals(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)) {
                    RtpCapabilities capabilities = mFactory.getRtpSenderCapabilities(transceiver.getMediaType());
                    for (RtpCapabilities.CodecCapability codec : capabilities.codecs) {
                        Map<String, Object> codecDict = SerializeUtils.serializeRtpCapabilitiesCodec(codec).toHashMap();
                        availableCodecs.add(new Pair<>(codecDict, codec));
                    }
                }

                if (direction.equals(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        || direction.equals(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)) {
                    RtpCapabilities capabilities = mFactory.getRtpReceiverCapabilities(transceiver.getMediaType());
                    for (RtpCapabilities.CodecCapability codec : capabilities.codecs) {
                        Map<String, Object> codecDict = SerializeUtils.serializeRtpCapabilitiesCodec(codec).toHashMap();
                        availableCodecs.add(new Pair<>(codecDict, codec));
                    }
                }

                // Codec preferences is order sensitive.
                List<RtpCapabilities.CodecCapability> codecsToSet = new ArrayList<>();

                for (int i = 0; i < codecPreferences.size(); i++) {
                    Map<String, Object> codecPref = codecPreferences.getMap(i).toHashMap();
                    for (Pair<Map<String, Object>, RtpCapabilities.CodecCapability> pair : availableCodecs) {
                        Map<String, Object> availableCodecDict = pair.first;
                        if (codecPref.equals(availableCodecDict)) {
                            codecsToSet.add(pair.second);
                            break;
                        }
                    }
                }

                transceiver.setCodecPreferences(codecsToSet);
            } catch (Exception e) {
                Log.d(TAG, "transceiverSetCodecPreferences(): " + e.getMessage());
            }
        });
        return true;
    }

    @ReactMethod
    public void getDisplayMedia(Promise promise) {
        ThreadUtils.runOnExecutor(() -> getUserMediaImpl.getDisplayMedia(promise));
    }

    @ReactMethod
    public void getUserMedia(ReadableMap constraints, Callback successCallback, Callback errorCallback) {
        ThreadUtils.runOnExecutor(() -> getUserMediaImpl.getUserMedia(constraints, successCallback, errorCallback));
    }

    @ReactMethod
    public void enumerateDevices(Callback callback) {
        ThreadUtils.runOnExecutor(() -> callback.invoke(getUserMediaImpl.enumerateDevices()));
    }

    @ReactMethod
    public void mediaStreamCreate(String id) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStream mediaStream = mFactory.createLocalMediaStream(id);
            localStreams.put(id, mediaStream);
        });
    }

    @ReactMethod
    public void mediaStreamAddTrack(String streamId, int pcId, String trackId) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStream stream = localStreams.get(streamId);
            if (stream == null) {
                Log.d(TAG, "mediaStreamAddTrack() could not find stream " + streamId);
                return;
            }

            MediaStreamTrack track = getTrack(pcId, trackId);
            if (track == null) {
                Log.d(TAG, "mediaStreamAddTrack() could not find track " + trackId);
                return;
            }

            String kind = track.kind();
            if ("audio".equals(kind)) {
                stream.addTrack((AudioTrack) track);
            } else if ("video".equals(kind)) {
                stream.addTrack((VideoTrack) track);
            }
        });
    }

    @ReactMethod
    public void mediaStreamRemoveTrack(String streamId, int pcId, String trackId) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStream stream = localStreams.get(streamId);
            if (stream == null) {
                Log.d(TAG, "mediaStreamRemoveTrack() could not find stream " + streamId);
                return;
            }

            MediaStreamTrack track = getTrack(pcId, trackId);
            if (track == null) {
                Log.d(TAG, "mediaStreamRemoveTrack() could not find track " + trackId);
                return;
            }

            String kind = track.kind();
            if ("audio".equals(kind)) {
                stream.removeTrack((AudioTrack) track);
            } else if ("video".equals(kind)) {
                stream.removeTrack((VideoTrack) track);
            }
        });
    }

    @ReactMethod
    public void mediaStreamRelease(String id) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStream stream = localStreams.get(id);
            if (stream == null) {
                Log.d(TAG, "mediaStreamRelease() stream is null");
                return;
            }
            localStreams.remove(id);
            stream.dispose();
        });
    }

    @ReactMethod
    public void mediaStreamTrackRelease(String id) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStreamTrack track = getLocalTrack(id);
            if (track == null) {
                Log.d(TAG, "mediaStreamTrackRelease() track is null");
                return;
            }
            track.setEnabled(false);
            getUserMediaImpl.disposeTrack(id);
        });
    }

    @ReactMethod
    public void mediaStreamTrackSetEnabled(int pcId, String id, boolean enabled) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStreamTrack track = getTrack(pcId, id);
            if (track == null) {
                Log.d(TAG, "mediaStreamTrackSetEnabled() could not find track " + id);
                return;
            }

            if (track.enabled() == enabled) {
                return;
            }
            track.setEnabled(enabled);
            getUserMediaImpl.mediaStreamTrackSetEnabled(id, enabled);
        });
    }

    @ReactMethod
    public void mediaStreamTrackApplyConstraints(String id, ReadableMap constraints, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStreamTrack track = getLocalTrack(id);
            if (track != null) {
                getUserMediaImpl.applyConstraints(id, constraints, promise);
            } else {
                promise.reject(new Exception("mediaStreamTrackApplyConstraints() could not find track " + id));
            }
        });
    }

    @ReactMethod
    public void mediaStreamTrackSetVolume(int pcId, String id, double volume) {
        ThreadUtils.runOnExecutor(() -> {
            MediaStreamTrack track = getTrack(pcId, id);
            if (track == null) {
                Log.d(TAG, "mediaStreamTrackSetVolume() could not find track " + id);
                return;
            }

            if (!(track instanceof AudioTrack)) {
                Log.d(TAG, "mediaStreamTrackSetVolume() track is not an AudioTrack!");
                return;
            }

            ((AudioTrack) track).setVolume(volume);
        });
    }

    /**
     * This serializes the transceivers current direction and mid and returns them
     * for update when an sdp negotiation/renegotiation happens
     */
    private ReadableArray getTransceiversInfo(PeerConnection peerConnection) {
        WritableArray transceiverUpdates = Arguments.createArray();

        for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
            WritableMap transceiverUpdate = Arguments.createMap();

            RtpTransceiver.RtpTransceiverDirection direction = transceiver.getCurrentDirection();
            if (direction != null) {
                String directionSerialized = SerializeUtils.serializeDirection(direction);
                transceiverUpdate.putString("currentDirection", directionSerialized);
            }

            transceiverUpdate.putString("transceiverId", transceiver.getSender().id());
            transceiverUpdate.putString("mid", transceiver.getMid());
            transceiverUpdate.putBoolean("isStopped", transceiver.isStopped());
            transceiverUpdate.putMap("senderRtpParameters",
                    SerializeUtils.serializeRtpParameters(transceiver.getSender().getParameters()));
            transceiverUpdate.putMap("receiverRtpParameters",
                    SerializeUtils.serializeRtpParameters(transceiver.getReceiver().getParameters()));
            transceiverUpdates.pushMap(transceiverUpdate);
        }
        return transceiverUpdates;
    }

    @ReactMethod
    public void mediaStreamTrackSetVideoEffects(String id, ReadableArray names) {
        ThreadUtils.runOnExecutor(() -> { getUserMediaImpl.setVideoEffects(id, names); });
    }

    @ReactMethod
    public void peerConnectionSetConfiguration(ReadableMap configuration, int id) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnection peerConnection = getPeerConnection(id);
            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionSetConfiguration() peerConnection is null");
                return;
            }
            peerConnection.setConfiguration(parseRTCConfiguration(configuration));
        });
    }

    @ReactMethod
    public void peerConnectionCreateOffer(int id, ReadableMap options, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
            PeerConnection peerConnection = pco.getPeerConnection();

            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionCreateOffer() peerConnection is null");
                promise.reject(new Exception("PeerConnection not found"));
                return;
            }

            List<String> receiversIds = new ArrayList<>();
            for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                receiversIds.add(transceiver.getReceiver().id());
            }

            final SdpObserver observer = new SdpObserver() {
                @Override
                public void onCreateFailure(String s) {
                    ThreadUtils.runOnExecutor(() -> { promise.reject("E_OPERATION_ERROR", s); });
                }

                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    ThreadUtils.runOnExecutor(() -> {
                        WritableMap params = Arguments.createMap();
                        WritableMap sdpInfo = Arguments.createMap();

                        sdpInfo.putString("sdp", sdp.description);
                        sdpInfo.putString("type", sdp.type.canonicalForm());

                        params.putArray("transceiversInfo", getTransceiversInfo(peerConnection));
                        params.putMap("sdpInfo", sdpInfo);

                        WritableArray newTransceivers = Arguments.createArray();
                        for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                            if (!receiversIds.contains(transceiver.getReceiver().id())) {
                                WritableMap newTransceiver = Arguments.createMap();
                                newTransceiver.putInt("transceiverOrder", pco.getNextTransceiverId());
                                newTransceiver.putMap(
                                        "transceiver", SerializeUtils.serializeTransceiver(id, transceiver));
                                newTransceivers.pushMap(newTransceiver);
                            }
                        }

                        params.putArray("newTransceivers", newTransceivers);

                        promise.resolve(params);
                    });
                }

                @Override
                public void onSetFailure(String s) {}

                @Override
                public void onSetSuccess() {}
            };

            peerConnection.createOffer(observer, constraintsForOptions(options));
        });
    }

    @ReactMethod
    public void peerConnectionCreateAnswer(int id, ReadableMap options, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnection peerConnection = getPeerConnection(id);

            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionCreateAnswer() peerConnection is null");
                promise.reject(new Exception("PeerConnection not found"));
                return;
            }

            final SdpObserver observer = new SdpObserver() {
                @Override
                public void onCreateFailure(String s) {
                    ThreadUtils.runOnExecutor(() -> { promise.reject("E_OPERATION_ERROR", s); });
                }

                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    ThreadUtils.runOnExecutor(() -> {
                        WritableMap params = Arguments.createMap();
                        WritableMap sdpInfo = Arguments.createMap();

                        sdpInfo.putString("sdp", sdp.description);
                        sdpInfo.putString("type", sdp.type.canonicalForm());

                        params.putArray("transceiversInfo", getTransceiversInfo(peerConnection));
                        params.putMap("sdpInfo", sdpInfo);

                        promise.resolve(params);
                    });
                }

                @Override
                public void onSetFailure(String s) {}

                @Override
                public void onSetSuccess() {}
            };

            peerConnection.createAnswer(observer, constraintsForOptions(options));
        });
    }

    @ReactMethod
    public void peerConnectionSetLocalDescription(int pcId, ReadableMap desc, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnection peerConnection = getPeerConnection(pcId);
            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionSetLocalDescription() peerConnection is null");
                promise.reject(new Exception("PeerConnection not found"));
                return;
            }

            final SdpObserver observer = new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {}

                @Override
                public void onSetSuccess() {
                    ThreadUtils.runOnExecutor(() -> {
                        WritableMap newSdpMap = Arguments.createMap();
                        WritableMap params = Arguments.createMap();

                        SessionDescription newSdp = peerConnection.getLocalDescription();
                        // Can happen when doing a rollback.
                        if (newSdp != null) {
                            newSdpMap.putString("type", newSdp.type.canonicalForm());
                            newSdpMap.putString("sdp", newSdp.description);
                        }

                        params.putMap("sdpInfo", newSdpMap);
                        params.putArray("transceiversInfo", getTransceiversInfo(peerConnection));

                        promise.resolve(params);
                    });
                }

                @Override
                public void onCreateFailure(String s) {}

                @Override
                public void onSetFailure(String s) {
                    ThreadUtils.runOnExecutor(() -> { promise.reject("E_OPERATION_ERROR", s); });
                }
            };

            if (desc != null) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(Objects.requireNonNull(desc.getString("type"))),
                        desc.getString("sdp"));

                peerConnection.setLocalDescription(observer, sdp);
            } else {
                peerConnection.setLocalDescription(observer);
            }
        });
    }

    @ReactMethod
    public void peerConnectionSetRemoteDescription(int id, ReadableMap desc, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
            PeerConnection peerConnection = pco.getPeerConnection();

            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionSetRemoteDescription() peerConnection is null");
                promise.reject(new Exception("PeerConnection not found"));
                return;
            }

            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(desc.getString("type")), desc.getString("sdp"));

            List<String> receiversIds = new ArrayList<>();
            for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                receiversIds.add(transceiver.getReceiver().id());
            }

            final SdpObserver observer = new SdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {}

                @Override
                public void onSetSuccess() {
                    ThreadUtils.runOnExecutor(() -> {
                        WritableMap newSdpMap = Arguments.createMap();
                        WritableMap params = Arguments.createMap();

                        SessionDescription newSdp = peerConnection.getRemoteDescription();
                        // Be defensive for the rollback cases.
                        if (newSdp != null) {
                            newSdpMap.putString("type", newSdp.type.canonicalForm());
                            newSdpMap.putString("sdp", newSdp.description);
                        }

                        params.putArray("transceiversInfo", getTransceiversInfo(peerConnection));
                        params.putMap("sdpInfo", newSdpMap);

                        WritableArray newTransceivers = Arguments.createArray();
                        for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                            if (!receiversIds.contains(transceiver.getReceiver().id())) {
                                WritableMap newTransceiver = Arguments.createMap();
                                newTransceiver.putInt("transceiverOrder", pco.getNextTransceiverId());
                                newTransceiver.putMap(
                                        "transceiver", SerializeUtils.serializeTransceiver(id, transceiver));
                                newTransceivers.pushMap(newTransceiver);
                            }
                        }

                        params.putArray("newTransceivers", newTransceivers);

                        promise.resolve(params);
                    });
                }

                @Override
                public void onCreateFailure(String s) {}

                @Override
                public void onSetFailure(String s) {
                    ThreadUtils.runOnExecutor(() -> { promise.reject("E_OPERATION_ERROR", s); });
                }
            };

            peerConnection.setRemoteDescription(observer, sdp);
        });
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap receiverGetCapabilities(String kind) {
        try {
            return (WritableMap) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        MediaStreamTrack.MediaType mediaType;
                        if (kind.equals("audio")) {
                            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO;
                        } else if (kind.equals("video")) {
                            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO;
                        } else {
                            return Arguments.createMap();
                        }

                        RtpCapabilities capabilities = mFactory.getRtpReceiverCapabilities(mediaType);
                        return SerializeUtils.serializeRtpCapabilities(capabilities);
                    })
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            Log.d(TAG, "receiverGetCapabilities() " + e.getMessage());
            return null;
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap senderGetCapabilities(String kind) {
        try {
            return (WritableMap) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        MediaStreamTrack.MediaType mediaType;
                        if (kind.equals("audio")) {
                            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO;
                        } else if (kind.equals("video")) {
                            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO;
                        } else {
                            return Arguments.createMap();
                        }

                        RtpCapabilities capabilities = mFactory.getRtpSenderCapabilities(mediaType);
                        return SerializeUtils.serializeRtpCapabilities(capabilities);
                    })
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            Log.d(TAG, "senderGetCapabilities() " + e.getMessage());
            return null;
        }
    }

    @ReactMethod
    public void receiverGetStats(int pcId, String receiverId, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(pcId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "receiverGetStats() peerConnection is null");
                promise.resolve(StringUtils.statsToJSON(new RTCStatsReport(0, new HashMap<>())));
            } else {
                pco.receiverGetStats(receiverId, promise);
            }
        });
    }

    @ReactMethod
    public void senderGetStats(int pcId, String senderId, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(pcId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "senderGetStats() peerConnection is null");
                promise.resolve(StringUtils.statsToJSON(new RTCStatsReport(0, new HashMap<>())));
            } else {
                pco.senderGetStats(senderId, promise);
            }
        });
    }

    @ReactMethod
    public void peerConnectionAddICECandidate(int pcId, ReadableMap candidateMap, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnection peerConnection = getPeerConnection(pcId);
            if (peerConnection == null) {
                Log.d(TAG, "peerConnectionAddICECandidate() peerConnection is null");
                promise.reject(new Exception("PeerConnection not found"));
                return;
            }

            if (!candidateMap.hasKey("sdpMid") && !candidateMap.hasKey("sdpMLineIndex")) {
                promise.reject("E_TYPE_ERROR", "Invalid argument");
                return;
            }

            IceCandidate candidate = new IceCandidate(candidateMap.hasKey("sdpMid") && !candidateMap.isNull("sdpMid")
                            ? candidateMap.getString("sdpMid")
                            : "",
                    candidateMap.hasKey("sdpMLineIndex") && !candidateMap.isNull("sdpMLineIndex")
                            ? candidateMap.getInt("sdpMLineIndex")
                            : 0,
                    candidateMap.getString("candidate"));

            peerConnection.addIceCandidate(candidate, new AddIceObserver() {
                @Override
                public void onAddSuccess() {
                    ThreadUtils.runOnExecutor(() -> {
                        WritableMap newSdpMap = Arguments.createMap();
                        SessionDescription newSdp = peerConnection.getRemoteDescription();
                        newSdpMap.putString("type", newSdp.type.canonicalForm());
                        newSdpMap.putString("sdp", newSdp.description);
                        promise.resolve(newSdpMap);
                    });
                }

                @Override
                public void onAddFailure(String s) {
                    ThreadUtils.runOnExecutor(() -> { promise.reject("E_OPERATION_ERROR", s); });
                }
            });
        });
    }

    @ReactMethod
    public void peerConnectionGetStats(int peerConnectionId, Promise promise) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "peerConnectionGetStats() peerConnection is null");
                promise.resolve(StringUtils.statsToJSON(new RTCStatsReport(0, new HashMap<>())));
            } else {
                pco.getStats(promise);
            }
        });
    }

    @ReactMethod
    public void peerConnectionClose(int id) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "peerConnectionClose() peerConnection is null");
                return;
            }
            pco.close();
        });
    }

    @ReactMethod
    public void peerConnectionDispose(int id) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "peerConnectionDispose() peerConnection is null");
            }
            pco.dispose();
            mPeerConnectionObservers.remove(id);
        });
    }

    @ReactMethod
    public void peerConnectionRestartIce(int pcId) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnection peerConnection = getPeerConnection(pcId);
            if (peerConnection == null) {
                Log.w(TAG, "peerConnectionRestartIce() peerConnection is null");
                return;
            }

            peerConnection.restartIce();
        });
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap createDataChannel(int peerConnectionId, String label, ReadableMap config) {
        try {
            return (WritableMap) ThreadUtils
                    .submitToExecutor((Callable<Object>) () -> {
                        PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
                        if (pco == null || pco.getPeerConnection() == null) {
                            Log.d(TAG, "createDataChannel() peerConnection is null");
                            return null;
                        } else {
                            return pco.createDataChannel(label, config);
                        }
                    })
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }

    @ReactMethod
    public void dataChannelClose(int peerConnectionId, String reactTag) {
        ThreadUtils.runOnExecutor(() -> {
            // Forward to PeerConnectionObserver which deals with DataChannels
            // because DataChannel is owned by PeerConnection.
            PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "dataChannelClose() peerConnection is null");
                return;
            }

            pco.dataChannelClose(reactTag);
        });
    }

    @ReactMethod
    public void dataChannelDispose(int peerConnectionId, String reactTag) {
        ThreadUtils.runOnExecutor(() -> {
            PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "dataChannelDispose() peerConnection is null");
                return;
            }

            pco.dataChannelDispose(reactTag);
        });
    }

    @ReactMethod
    public void dataChannelSend(int peerConnectionId, String reactTag, String data, String type) {
        ThreadUtils.runOnExecutor(() -> {
            // Forward to PeerConnectionObserver which deals with DataChannels
            // because DataChannel is owned by PeerConnection.
            PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
            if (pco == null || pco.getPeerConnection() == null) {
                Log.d(TAG, "dataChannelSend() peerConnection is null");
                return;
            }

            pco.dataChannelSend(reactTag, data, type);
        });
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    // =====================================================
    // VideoFrameCaptureModule 代理方法
    // =====================================================

    @ReactMethod
    public void startCapture(String trackId, @Nullable ReadableMap config, Promise promise) {
        Log.e(TAG, "===== WebRTCModule.startCapture called: " + trackId);
        VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
        Log.e(TAG, "===== WebRTCModule.startCapture: module found = " + (module != null));
        if (module != null) {
            Log.e(TAG, "===== WebRTCModule.startCapture: calling module.startCapture");
            module.startCapture(trackId, config, promise);
        } else {
            promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
        }
    }

    @ReactMethod
    public void stopCapture(String trackId, Promise promise) {
        Log.e(TAG, "===== WebRTCModule.stopCapture called: " + trackId);
        VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
        if (module != null) {
            module.stopCapture(trackId, promise);
        } else {
            promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
        }
    }

    @ReactMethod
    public void captureFrame(String trackId, Promise promise) {
        try {
            Log.e(TAG, "===== WebRTCModule.captureFrame called: " + trackId);
            // 直接使用 WebRTCModule.getInstance() 获取实例，然后通过它获取 VideoFrameCaptureModule
            WebRTCModule webRTCModule = WebRTCModule.getInstance();
            if (webRTCModule == null) {
                Log.e(TAG, "===== WebRTCModule.getInstance() returned null");
                promise.reject("MODULE_NOT_FOUND", "WebRTCModule instance not found");
                return;
            }
            
            // 通过 ReactContext 获取 VideoFrameCaptureModule
            VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
            Log.e(TAG, "===== WebRTCModule.captureFrame: module found = " + (module != null));
            
            if (module != null) {
                Log.e(TAG, "===== WebRTCModule.captureFrame: calling module.captureFrame");
                module.captureFrame(trackId, promise);
            } else {
                Log.e(TAG, "===== WebRTCModule.captureFrame: VideoFrameCaptureModule not found, trying alternative method");
                // 备选方案：直接使用 WebRTCModule 的 getVideoTrack 方法
                promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
            }
        } catch (Exception e) {
            Log.e(TAG, "WebRTCModule.captureFrame error", e);
            promise.reject("CAPTURE_ERROR", e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    @ReactMethod
    public void stopAllCaptures(Promise promise) {
        VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
        if (module != null) {
            module.stopAllCaptures(promise);
        } else {
            promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
        }
    }

    @ReactMethod
    public void getFrameData(String trackId, String format, Promise promise) {
        VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
        if (module != null) {
            module.getFrameData(trackId, format, promise);
        } else {
            promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
        }
    }

    @ReactMethod
    public void updateConfig(String trackId, ReadableMap config, Promise promise) {
        VideoFrameCaptureModule module = getReactApplicationContext().getNativeModule(VideoFrameCaptureModule.class);
        if (module != null) {
            module.updateConfig(trackId, config, promise);
        } else {
            promise.reject("MODULE_NOT_FOUND", "VideoFrameCaptureModule not found");
        }
    }
    
    /**
     * 获取 VideoTrack
     */
    private VideoTrack getVideoTrack(String trackId) {
        try {
            Map<String, MediaStream> localStreams = this.localStreams;
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
    
    /**
     * 临时的 VideoSink 用于捕获一帧
     */
    private class CaptureVideoSink implements VideoSink {
        private final Promise promise;
        private boolean captured = false;

        CaptureVideoSink(Promise promise) {
            this.promise = promise;
        }

        @Override
        public void onFrame(VideoFrame frame) {
            if (captured) return;
            captured = true;

            try {
                Log.e(TAG, "===== CaptureVideoSink.onFrame called");
                Bitmap bitmap = convertFrameToBitmap(frame);
                Log.e(TAG, "===== CaptureVideoSink: bitmap created: " + (bitmap != null ? "yes" : "no"));

                if (bitmap != null) {
                    WritableMap result = bitmapToResult(bitmap, frame.getTimestampNs());
                    Log.e(TAG, "===== CaptureVideoSink: result created, resolving promise");
                    promise.resolve(result);
                } else {
                    Log.e(TAG, "===== CaptureVideoSink: bitmap is null, rejecting promise");
                    promise.reject("CONVERSION_ERROR", "Failed to convert frame to bitmap");
                }
            } catch (Exception e) {
                Log.e(TAG, "CaptureVideoSink error", e);
                promise.reject("CONVERSION_ERROR", e.getMessage());
            }
        }

        private Bitmap convertFrameToBitmap(VideoFrame frame) {
            VideoFrame.Buffer buffer = frame.getBuffer();
            VideoFrame.I420Buffer i420Buffer = buffer.toI420();

            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();

            Log.e(TAG, "convertFrameToBitmap: starting conversion for " + width + "x" + height);

            // 获取 stride（行间距）
            int yStride = i420Buffer.getStrideY();
            int uStride = i420Buffer.getStrideU();
            int vStride = i420Buffer.getStrideV();

            Log.e(TAG, "convertFrameToBitmap: strides Y=" + yStride + ", U=" + uStride + ", V=" + vStride);

            // 创建 ARGB Bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] argbPixels = new int[width * height];

            // 获取 YUV 数据
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();

            byte[] yData = new byte[yStride * height];
            byte[] uData = new byte[uStride * (height / 2)];
            byte[] vData = new byte[vStride * (height / 2)];

            yBuffer.get(yData);
            uBuffer.get(uData);
            vBuffer.get(vData);

            // YUV 到 RGB 转换 (BT.601 标准)
            int pixelIndex = 0;
            for (int j = 0; j < height; j++) {
                int yRowIndex = j * yStride;
                int uvRowIndex = (j / 2) * uStride;

                for (int i = 0; i < width; i++) {
                    int y = yData[yRowIndex + i] & 0xff;
                    int u = uData[uvRowIndex + (i / 2)] & 0xff;
                    int v = vData[uvRowIndex + (i / 2)] & 0xff;

                    // YUV 到 RGB 转换公式 (BT.601)
                    int r = (int)((y - 16) * 1.164 + (v - 128) * 1.596);
                    int g = (int)((y - 16) * 1.164 - (u - 128) * 0.392 - (v - 128) * 0.813);
                    int b = (int)((y - 16) * 1.164 + (u - 128) * 2.017);

                    // 裁剪到 0-255 范围
                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));

                    argbPixels[pixelIndex++] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }

            bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height);
            i420Buffer.release();

            Log.e(TAG, "convertFrameToBitmap: conversion completed successfully");
            return bitmap;
        }

        private WritableMap bitmapToResult(Bitmap bitmap, long timestampNs) {
            WritableMap result = Arguments.createMap();
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os);

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
    }
}
