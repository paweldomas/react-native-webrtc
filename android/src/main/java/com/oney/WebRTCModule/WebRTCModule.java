package com.oney.WebRTCModule;

import android.app.Application;

import android.os.Handler;
import android.provider.ContactsContract;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.UUID;

import android.util.Base64;
import android.util.SparseArray;
import android.hardware.Camera;
import android.media.AudioManager;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.app.Activity;
import android.os.PowerManager;

import android.opengl.EGLContext;
import android.util.Log;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;

import org.webrtc.*;

public class WebRTCModule extends ReactContextBaseJavaModule {
    private final static String TAG = WebRTCModule.class.getCanonicalName();

    private static final String LANGUAGE =  "language";
    private PeerConnectionFactory mFactory;
    private final SparseArray<PeerConnection> mPeerConnections;
    public final Map<String, MediaStream> mMediaStreams;
    public final Map<String, MediaStreamTrack> mMediaStreamTracks;
    private final SparseArray<DataChannel> mDataChannels;
    private MediaConstraints pcConstraints = new MediaConstraints();
    VideoSource videoSource;

    public WebRTCModule(ReactApplicationContext reactContext) {
        super(reactContext);

        mPeerConnections = new SparseArray<PeerConnection>();
        mMediaStreams = new HashMap<String, MediaStream>();
        mMediaStreamTracks = new HashMap<String, MediaStreamTrack>();
        mDataChannels = new SparseArray<DataChannel>();

        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

        PeerConnectionFactory.initializeAndroidGlobals(reactContext, true, true, true);
        mFactory = new PeerConnectionFactory();
    }

    @Override
    public String getName() {
        return "WebRTCModule";
    }

    private String getCurrentLanguage(){
        Locale current = getReactApplicationContext().getResources().getConfiguration().locale;
        return current.getLanguage();
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put(LANGUAGE, getCurrentLanguage());
        return constants;
    }

    @ReactMethod
    public void getLanguage(Callback callback){
        String language = getCurrentLanguage();
        System.out.println("The current language is "+language);
        callback.invoke(null, language);
    }
    private void sendEvent(String eventName, @Nullable WritableMap params) {
        getReactApplicationContext()
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    private List<PeerConnection.IceServer> createIceServers(ReadableArray iceServersArray) {
        LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
        for (int i = 0; i < iceServersArray.size(); i++) {
            ReadableMap iceServerMap = iceServersArray.getMap(i);
            boolean hasUsernameAndCredential = iceServerMap.hasKey("username") && iceServerMap.hasKey("credential");
            if (iceServerMap.hasKey("url")) {
                if (hasUsernameAndCredential) {
                    iceServers.add(new PeerConnection.IceServer(iceServerMap.getString("url"),iceServerMap.getString("username"), iceServerMap.getString("credential")));
                } else {
                    iceServers.add(new PeerConnection.IceServer(iceServerMap.getString("url")));
                }
            } else if (iceServerMap.hasKey("urls")) {
                switch (iceServerMap.getType("urls")) {
                    case String:
                        if (hasUsernameAndCredential) {
                            iceServers.add(new PeerConnection.IceServer(iceServerMap.getString("urls"),iceServerMap.getString("username"), iceServerMap.getString("credential")));
                        } else {
                            iceServers.add(new PeerConnection.IceServer(iceServerMap.getString("urls")));
                        }
                        break;
                    case Array:
                        ReadableArray urls = iceServerMap.getArray("urls");
                        for (int j = 0; j < urls.size(); j++) {
                            String url = urls.getString(j);
                            if (hasUsernameAndCredential) {
                                iceServers.add(new PeerConnection.IceServer(url,iceServerMap.getString("username"), iceServerMap.getString("credential")));
                            } else {
                                iceServers.add(new PeerConnection.IceServer(url));
                            }
                        }
                        break;
                }
            }
        }
        return iceServers;
    }

    @ReactMethod
    public void peerConnectionInit(ReadableMap configuration, final int id){
        List<PeerConnection.IceServer> iceServers = createIceServers(configuration.getArray("iceServers"));

        PeerConnection peerConnection = mFactory.createPeerConnection(iceServers, pcConstraints, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                params.putString("signalingState", signalingStateString(signalingState));
                sendEvent("peerConnectionSignalingStateChanged", params);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                params.putString("iceConnectionState", iceConnectionStateString(iceConnectionState));

                sendEvent("peerConnectionIceConnectionChanged", params);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChangedwe" + iceGatheringState.name());
                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                params.putString("iceGatheringState", iceGatheringStateString(iceGatheringState));
                sendEvent("peerConnectionIceGatheringChanged", params);
            }

            @Override
            public void onIceCandidate(final IceCandidate candidate) {
                Log.d(TAG, "onIceCandidatewqerqwrsdfsd");
                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                WritableMap candidateParams = Arguments.createMap();
                candidateParams.putInt("sdpMLineIndex", candidate.sdpMLineIndex);
                candidateParams.putString("sdpMid", candidate.sdpMid);
                candidateParams.putString("candidate", candidate.sdp);
                params.putMap("candidate", candidateParams);

                sendEvent("peerConnectionGotICECandidate", params);
            }

            @Override
            public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
                Log.e(TAG, "Unhandled ICE candidate removed!");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                String streamId = mediaStream.label();
                if (mMediaStreams.containsKey(streamId)) {
                    Log.e(TAG,
                        "onAddStream: Duplicated stream for ID: " + streamId);
                    return;
                }
                mMediaStreams.put(streamId, mediaStream);
                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                params.putString("streamId", streamId);

                WritableArray tracks = Arguments.createArray();

                for (int i = 0; i < mediaStream.videoTracks.size(); i++) {
                    VideoTrack track = mediaStream.videoTracks.get(i);

                    WritableMap trackInfo = Arguments.createMap();
                    trackInfo.putString("id", track.id());
                    trackInfo.putString("label", "Video");
                    trackInfo.putString("kind", track.kind());
                    trackInfo.putBoolean("enabled", track.enabled());
                    trackInfo.putString("readyState", track.state().toString());
                    trackInfo.putBoolean("remote", true);
                    tracks.pushMap(trackInfo);
                }
                for (int i = 0; i < mediaStream.audioTracks.size(); i++) {
                    AudioTrack track = mediaStream.audioTracks.get(i);

                    WritableMap trackInfo = Arguments.createMap();
                    trackInfo.putString("id", track.id());
                    trackInfo.putString("label", "Audio");
                    trackInfo.putString("kind", track.kind());
                    trackInfo.putBoolean("enabled", track.enabled());
                    trackInfo.putString("readyState", track.state().toString());
                    trackInfo.putBoolean("remote", true);
                    tracks.pushMap(trackInfo);
                }
                params.putArray("tracks", tracks);

                sendEvent("peerConnectionAddedStream", params);
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                if (mediaStream != null) {
                    for (VideoTrack track : mediaStream.videoTracks) {
                        mMediaStreamTracks.remove(track.id());
                    }
                    for (AudioTrack track : mediaStream.audioTracks) {
                        mMediaStreamTracks.remove(track.id());
                    }
                    mMediaStreams.remove(mediaStream.label());
                }
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
            }

            @Override
            public void onRenegotiationNeeded() {
                WritableMap params = Arguments.createMap();
                params.putInt("id", id);
                sendEvent("peerConnectionOnRenegotiationNeeded", params);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean var1) {

            }
        });
        mPeerConnections.put(id, peerConnection);
    }

    private String getNextStreamUUID() {
        String uuid;

        do {
            uuid = UUID.randomUUID().toString();
        } while (mMediaStreams.containsKey(uuid));

        return uuid;
    }

    private String getNextTrackUUID() {
        String uuid;

        do {
            uuid = UUID.randomUUID().toString();
        } while (mMediaStreamTracks.containsKey(uuid));

        return uuid;
    }

    private void addDefaultAudioConstraints(MediaConstraints audioConstraints) {
        audioConstraints.optional.add(
            new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.optional.add(
            new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.optional.add(
            new MediaConstraints.KeyValuePair("echoCancellation", "true"));
        audioConstraints.optional.add(
            new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
        audioConstraints.optional.add(
            new MediaConstraints.KeyValuePair(
                    "googDAEchoCancellation", "true"));
    }

    private MediaConstraints parseConstraints(ReadableMap constraintsMap) {

        MediaConstraints mediaConstraints = new MediaConstraints();

        if (constraintsMap.getType("mandatory") == ReadableType.Map) {
            ReadableMap mandatory = constraintsMap.getMap("mandatory");
            ReadableMapKeySetIterator keyIterator = mandatory.keySetIterator();

            while (keyIterator.hasNextKey()) {
                String key = keyIterator.nextKey();
                String value = ReactBridgeUtil.getMapStrValue(mandatory, key);

                mediaConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(key, value));
            }
        } else {
            Log.d(TAG, "mandatory constraints are not a map");
        }

        if (constraintsMap.getType("optional") == ReadableType.Array) {
            ReadableArray options = constraintsMap.getArray("optional");

            for (int i = 0; i < options.size(); i++) {
                if (options.getType(i) == ReadableType.Map) {
                    ReadableMap option = options.getMap(i);
                    ReadableMapKeySetIterator keyIterator
                        = option.keySetIterator();
                    String key = keyIterator.nextKey();

                    if (key != null && !"sourceId".equals(key)) {
                        mediaConstraints.optional.add(
                            new MediaConstraints.KeyValuePair(
                                key,
                                ReactBridgeUtil.getMapStrValue(option, key)));
                    }
                }
            }
        } else {
            Log.d(TAG, "optional constraints are not a map");
        }

        return mediaConstraints;
    }

    private Integer getSourceIdConstraint(ReadableMap mediaConstraints) {
        if (mediaConstraints.hasKey("optional") &&
            mediaConstraints.getType("optional") == ReadableType.Array) {
            ReadableArray options = mediaConstraints.getArray("optional");

            for (int i = 0; i < options.size(); i++) {
                if (options.getType(i) == ReadableType.Map) {
                    ReadableMap option = options.getMap(i);

                    if (option.hasKey("sourceId") &&
                        option.getType("sourceId") == ReadableType.String) {
                        return Integer.parseInt(option.getString("sourceId"));
                    }
                }
            }
        }
        return null;
    }

    @ReactMethod
    public void getUserMedia(ReadableMap constraints, Callback successCb,
                             Callback    failureCb) {
        AudioTrack audioTrack = null;
        VideoTrack videoTrack = null;
        WritableArray tracks = Arguments.createArray();

        if (constraints.hasKey("video")) {
            ReadableType type = constraints.getType("video");
            VideoSource videoSource = null;
            MediaConstraints videoConstraints = new MediaConstraints();
            Integer sourceId = null;
            switch (type) {
                case Boolean:
                    if (!constraints.getBoolean("video")) {
                        videoConstraints = null;
                    }
                    break;
                case Map:
                    ReadableMap useVideoMap = constraints.getMap("video");
                    videoConstraints = parseConstraints(useVideoMap);
                    sourceId = getSourceIdConstraint(useVideoMap);
                    break;
            }

            if (videoConstraints != null) {
                Log.i(TAG, "getUserMedia(video): " + videoConstraints
                    + ", sourceId: " + sourceId);

                VideoCapturer videoCapturer = getVideoCapturerById(sourceId);
                if (videoCapturer != null) {
                    // FIXME it seems that the factory does not care about
                    //       given mandatory constraints too much
                    videoSource = mFactory.createVideoSource(
                            videoCapturer, videoConstraints);
                }
            }

            if (videoSource != null) {
                String trackId = getNextTrackUUID();
                videoTrack = mFactory.createVideoTrack(trackId, videoSource);
                if (videoTrack != null) {
                    mMediaStreamTracks.put(trackId, videoTrack);

                    WritableMap trackInfo = Arguments.createMap();
                    trackInfo.putString("id", trackId);
                    trackInfo.putString("label", "Video");
                    trackInfo.putString("kind", videoTrack.kind());
                    trackInfo.putBoolean("enabled", videoTrack.enabled());
                    trackInfo.putString(
                        "readyState", videoTrack.state().toString());
                    trackInfo.putBoolean("remote", false);
                    tracks.pushMap(trackInfo);
                }
            }

            if (videoConstraints != null && videoTrack == null) {
                failureCb.invoke("Failed to obtain video");
                return;
            }
        }

        if (constraints.hasKey("audio")) {
            MediaConstraints audioConstraints = new MediaConstraints();
            ReadableType type = constraints.getType("audio");
            switch (type) {
                case Boolean:
                    if (constraints.getBoolean("audio")) {
                        addDefaultAudioConstraints(audioConstraints);
                    } else {
                        audioConstraints = null;
                    }
                    break;
                case Map:
                    audioConstraints
                        = parseConstraints(constraints.getMap("audio"));
                    break;
                default:
                    audioConstraints = null;
                    break;
            }

            if (audioConstraints != null) {
                Log.i(TAG, "getUserMedia(audio): " + audioConstraints);

                AudioSource audioSource
                    = mFactory.createAudioSource(audioConstraints);

                if (audioSource != null) {
                    String trackId = getNextTrackUUID();
                    audioTrack
                        = mFactory.createAudioTrack(trackId, audioSource);
                    if (audioTrack != null) {
                        mMediaStreamTracks.put(trackId, audioTrack);

                        WritableMap trackInfo = Arguments.createMap();
                        trackInfo.putString("id", trackId);
                        trackInfo.putString("label", "Audio");
                        trackInfo.putString("kind", audioTrack.kind());
                        trackInfo.putBoolean("enabled", audioTrack.enabled());
                        trackInfo.putString("readyState",
                            audioTrack.state().toString());
                        trackInfo.putBoolean("remote", false);
                        tracks.pushMap(trackInfo);
                    }
                }
            }
            if (audioTrack == null && audioConstraints != null) {
                failureCb.invoke("Failed to obtain audio");
                return;
            }
        }

        if (audioTrack == null && videoTrack == null) {
            failureCb.invoke("No audio nor video media requested");
            return;
        }

        String streamId = getNextStreamUUID();
        MediaStream mediaStream = mFactory.createLocalMediaStream(streamId);
        if (mediaStream == null) {
            failureCb.invoke("Failed to create new media stream");
            return;
        }

        if (audioTrack != null)
            mediaStream.addTrack(audioTrack);
        if (videoTrack != null)
            mediaStream.addTrack(videoTrack);

        Log.d(TAG, "mMediaStreamId: " + streamId);
        mMediaStreams.put(streamId, mediaStream);

        successCb.invoke(streamId, tracks);
    }
    @ReactMethod
    public void mediaStreamTrackGetSources(Callback callback){
        WritableArray array = Arguments.createArray();
        String[] names = new String[Camera.getNumberOfCameras()];

        for(int i = 0; i < Camera.getNumberOfCameras(); ++i) {
            WritableMap info = getCameraInfo(i);
            if (info != null) {
                array.pushMap(info);
            }
        }

        WritableMap audio = Arguments.createMap();
        audio.putString("label", "Audio");
        audio.putString("id", "audio-1");
        audio.putString("facing", "");
        audio.putString("kind", "audio");

        array.pushMap(audio);
        callback.invoke(array);
    }

    @ReactMethod
    public void mediaStreamTrackStop(final String id) {
        MediaStreamTrack track = mMediaStreamTracks.get(id);
        if (track == null) {
            Log.d(TAG, "mediaStreamTrackStop() track is null");
            return;
        }
        track.setEnabled(false);
        //track.setState(MediaStreamTrack.State.ENDED);
        mMediaStreamTracks.remove(id);
        // what exaclty `detached` means in doc?
        // see: https://www.w3.org/TR/mediacapture-streams/#track-detached
    }

    @ReactMethod
    public void mediaStreamTrackSetEnabled(final String id, final boolean enabled) {
        MediaStreamTrack track = mMediaStreamTracks.get(id);
        if (track == null) {
            Log.d(TAG, "mediaStreamTrackSetEnabled() track is null");
            return;
        } else if (track.enabled() == enabled) {
            return;
        }
        track.setEnabled(enabled);
    }

    @ReactMethod
    public void mediaStreamTrackRelease(final String streamId, final String _trackId) {
        MediaStream stream = mMediaStreams.get(streamId);
        if (stream == null) {
            Log.d(TAG, "mediaStreamTrackRelease() stream is null");
            return;
        }
        MediaStreamTrack track = mMediaStreamTracks.get(_trackId);
        if (track == null) {
            Log.d(TAG, "mediaStreamTrackRelease() track is null");
            return;
        }
        track.setEnabled(false); // should we do this?
        //track.setState(MediaStreamTrack.State.ENDED); // should we do this?
        mMediaStreamTracks.remove(_trackId);
        if (track.kind().equals("audio")) {
            stream.removeTrack((AudioTrack)track);
        } else if (track.kind().equals("video")) {
            stream.removeTrack((VideoTrack)track);
        }
    }

    public WritableMap getCameraInfo(int index) {
        CameraInfo info = new CameraInfo();

        try {
            Camera.getCameraInfo(index, info);
        } catch (Exception var3) {
            Logging.e("CameraEnumerationAndroid", "getCameraInfo failed on index " + index, var3);
            return null;
        }
        WritableMap params = Arguments.createMap();
        String facing = info.facing == 1 ? "front" : "back";
        params.putString("label", "Camera " + index + ", Facing " + facing + ", Orientation " + info.orientation);
        params.putString("id", "" + index);
        params.putString("facing", facing);
        params.putString("kind", "video");

        return params;
    }

    private VideoCapturer getVideoCapturerById(Integer id) {
        String name
            = id != null ? CameraEnumerationAndroid.getDeviceName(id) : null;
        if (name == null) {
            name = CameraEnumerationAndroid.getNameOfFrontFacingDevice();
        }

        return VideoCapturerAndroid.create(name,
            new CameraVideoCapturer.CameraEventsHandler() {
            // Camera error handler - invoked when camera can not be opened
            // or any camera exception happens on camera thread.
            public void onCameraError(String errorDescription) {
                Log.e(TAG, "onCameraError: " + errorDescription);
            }

            // Invoked when camera stops receiving frames.
            public void onCameraFreezed(String errorDescription) {
                Log.e(TAG, "onCameraFreezed: " + errorDescription);
            }

            // Callback invoked when camera is opening.
            public void onCameraOpening(int cameraId) {
                Log.i(TAG, "onCameraOpening: " + cameraId);
            }

            // Callback invoked when first camera frame is available after camera is started.
            public void onFirstFrameAvailable() {
                Log.i(TAG, "onFirstFrameAvailable");
            }

            // Callback invoked when camera is closed.
            public void onCameraClosed() {
                Log.i(TAG, "onCameraClosed");
            }
        });
    }
    private MediaConstraints defaultConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        // TODO video media
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        return constraints;
    }
    @ReactMethod
    public void peerConnectionAddStream(final String streamId, final int id){
        MediaStream mediaStream = mMediaStreams.get(streamId);
        if (mediaStream == null) {
            Log.d(TAG, "peerConnectionAddStream() mediaStream is null");
            return;
        }
        PeerConnection peerConnection = mPeerConnections.get(id);
        if (peerConnection != null) {
            boolean result = peerConnection.addStream(mediaStream);
            Log.d(TAG, "addStream" + result);
        } else {
            Log.d(TAG, "peerConnectionAddStream() peerConnection is null");
        }
    }
    @ReactMethod
    public void peerConnectionRemoveStream(final String streamId, final int id){
        MediaStream mediaStream = mMediaStreams.get(streamId);
        if (mediaStream == null) {
            Log.d(TAG, "peerConnectionRemoveStream() mediaStream is null");
            return;
        }
        PeerConnection peerConnection = mPeerConnections.get(id);
        if (peerConnection != null) {
            peerConnection.removeStream(mediaStream);
        } else {
            Log.d(TAG, "peerConnectionRemoveStream() peerConnection is null");
        }
    }

    @ReactMethod
    public void peerConnectionCreateOffer(final int id, final Callback callback) {
        PeerConnection peerConnection = mPeerConnections.get(id, null);

        // MediaConstraints constraints = new MediaConstraints();
        // constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        // constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        Log.d(TAG, "RTCPeerConnectionCreateOfferWithObjectID start");
        if (peerConnection != null) {
            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {
                    WritableMap params = Arguments.createMap();
                    params.putString("type", sdp.type.canonicalForm());
                    params.putString("sdp", sdp.description);
                    callback.invoke(true, params);
                }
                @Override
                public void onSetSuccess() {}

                @Override
                public void onCreateFailure(String s) {
                    callback.invoke(false, s);
                }

                @Override
                public void onSetFailure(String s) {}
            }, pcConstraints);
        } else {
            Log.d(TAG, "peerConnectionCreateOffer() peerConnection is null");
            callback.invoke(false, "peerConnection is null");
        }
        Log.d(TAG, "RTCPeerConnectionCreateOfferWithObjectID end");
    }

    @ReactMethod
    public void peerConnectionCreateAnswer(final int id, final Callback callback) {
        PeerConnection peerConnection = mPeerConnections.get(id, null);

        // MediaConstraints constraints = new MediaConstraints();
        // constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        // constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        Log.d(TAG, "RTCPeerConnectionCreateAnswerWithObjectID start");
        if (peerConnection != null) {
            peerConnection.createAnswer(new SdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {
                    WritableMap params = Arguments.createMap();
                    params.putString("type", sdp.type.canonicalForm());
                    params.putString("sdp", sdp.description);
                    callback.invoke(true, params);
                }

                @Override
                public void onSetSuccess() {
                }

                @Override
                public void onCreateFailure(String s) {
                    callback.invoke(false, s);
                }

                @Override
                public void onSetFailure(String s) {
                }
            }, pcConstraints);
        } else {
            Log.d(TAG, "peerConnectionCreateAnswer() peerConnection is null");
            callback.invoke(false, "peerConnection is null");
        }
        Log.d(TAG, "RTCPeerConnectionCreateAnswerWithObjectID end");
    }

    @ReactMethod
    public void peerConnectionSetLocalDescription(ReadableMap sdpMap, final int id, final Callback callback) {
        PeerConnection peerConnection = mPeerConnections.get(id, null);

        Log.d(TAG, "peerConnectionSetLocalDescription() start");
        if (peerConnection != null) {
            SessionDescription sdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpMap.getString("type")),
                sdpMap.getString("sdp")
            );

            peerConnection.setLocalDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {
                }

                @Override
                public void onSetSuccess() {
                    callback.invoke(true);
                }

                @Override
                public void onCreateFailure(String s) {
                }

                @Override
                public void onSetFailure(String s) {
                    callback.invoke(false, s);
                }
            }, sdp);
        } else {
            Log.d(TAG, "peerConnectionSetLocalDescription() peerConnection is null");
            callback.invoke(false, "peerConnection is null");
        }
        Log.d(TAG, "peerConnectionSetLocalDescription() end");
    }
    @ReactMethod
    public void peerConnectionSetRemoteDescription(final ReadableMap sdpMap, final int id, final Callback callback) {
        PeerConnection peerConnection = mPeerConnections.get(id, null);
        // final String d = sdpMap.getString("type");

        Log.d(TAG, "peerConnectionSetRemoteDescription() start");
        if (peerConnection != null) {
            SessionDescription sdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpMap.getString("type")),
                sdpMap.getString("sdp")
            );

            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(final SessionDescription sdp) {
                }

                @Override
                public void onSetSuccess() {
                    callback.invoke(true);
                }

                @Override
                public void onCreateFailure(String s) {
                }

                @Override
                public void onSetFailure(String s) {
                    callback.invoke(false, s);
                }
            }, sdp);
        } else {
            Log.d(TAG, "peerConnectionSetRemoteDescription() peerConnection is null");
            callback.invoke(false, "peerConnection is null");
        }
        Log.d(TAG, "peerConnectionSetRemoteDescription() end");
    }
    @ReactMethod
    public void peerConnectionAddICECandidate(ReadableMap candidateMap, final int id, final Callback callback) {
        boolean result = false;
        PeerConnection peerConnection = mPeerConnections.get(id, null);
        Log.d(TAG, "peerConnectionAddICECandidate() start");
        if (peerConnection != null) {
            IceCandidate candidate = new IceCandidate(
                candidateMap.getString("sdpMid"),
                candidateMap.getInt("sdpMLineIndex"),
                candidateMap.getString("candidate")
            );
            result = peerConnection.addIceCandidate(candidate);
        } else {
            Log.d(TAG, "peerConnectionAddICECandidate() peerConnection is null");
        }
        callback.invoke(result);
        Log.d(TAG, "peerConnectionAddICECandidate() end");
    }
    @ReactMethod
    public void peerConnectionClose(final int id) {
        PeerConnection peerConnection = mPeerConnections.get(id, null);
        if (peerConnection != null) {
            peerConnection.close();
            mPeerConnections.remove(id);
        } else {
            Log.d(TAG, "peerConnectionClose() peerConnection is null");
        }
        resetAudio();
    }
    @ReactMethod
    public void mediaStreamRelease(final String id) {
        MediaStream mediaStream = mMediaStreams.get(id);
        if (mediaStream != null) {
            for (VideoTrack track : mediaStream.videoTracks) {
                mMediaStreamTracks.remove(track);
            }
            for (AudioTrack track : mediaStream.audioTracks) {
                mMediaStreamTracks.remove(track);
            }

            mMediaStreams.remove(id);
        } else {
            Log.d(TAG, "mediaStreamRelease() mediaStream is null");
        }
    }
    private void resetAudio() {
        AudioManager audioManager = (AudioManager)getReactApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }
    @ReactMethod
    public void setAudioOutput(String output) {
        AudioManager audioManager = (AudioManager)getReactApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        audioManager.setSpeakerphoneOn(output.equals("speaker"));
    }
    @ReactMethod
    public void setKeepScreenOn(final boolean isOn) {
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Window window = getCurrentActivity().getWindow();
                if (isOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
    }

    @ReactMethod
    public void setProximityScreenOff(boolean enabled) {
        // TODO
        /*
        PowerManager powerManager = (PowerManager)getReactApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
            wakeLock.setReferenceCounted(false);
        } else {
        }*/
    }

    @ReactMethod
    public void dataChannelInit(final int peerConnectionId, final int dataChannelId, String label, ReadableMap config) {
        PeerConnection peerConnection = mPeerConnections.get(peerConnectionId, null);
        if (peerConnection != null) {
            DataChannel.Init init = new DataChannel.Init();
            init.id = dataChannelId;
            if (config != null) {
                if (config.hasKey("ordered")) {
                    init.ordered = config.getBoolean("ordered");
                }
                if (config.hasKey("maxRetransmitTime")) {
                    init.maxRetransmitTimeMs = config.getInt("maxRetransmitTime");
                }
                if (config.hasKey("maxRetransmits")) {
                    init.maxRetransmits = config.getInt("maxRetransmits");
                }
                if (config.hasKey("protocol")) {
                    init.protocol = config.getString("protocol");
                }
                if (config.hasKey("negotiated")) {
                    init.ordered = config.getBoolean("negotiated");
                }
            }
            DataChannel dataChannel = peerConnection.createDataChannel(label, init);
            mDataChannels.put(dataChannelId, dataChannel);
        } else {
            Log.d(TAG, "dataChannelInit() peerConnection is null");
        }
    }

    @ReactMethod
    public void dataChannelSend(final int dataChannelId, String data, String type) {
        DataChannel dataChannel = mDataChannels.get(dataChannelId, null);
        if (dataChannel != null) {
            byte[] byteArray;
            if (type.equals("text")) {
                try {
                    byteArray = data.getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    Log.d(TAG, "Could not encode text string as UTF-8.");
                    return;
                }
            } else if (type.equals("binary")) {
                byteArray = Base64.decode(data, Base64.NO_WRAP);
            } else {
                Log.e(TAG, "Unsupported data type: " + type);
                return;
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
            DataChannel.Buffer buffer = new DataChannel.Buffer(byteBuffer, type.equals("binary"));
            dataChannel.send(buffer);
        } else {
            Log.d(TAG, "dataChannelSend() dataChannel is null");
        }
    }

    @ReactMethod
    public void dataChannelClose(final int dataChannelId) {
        DataChannel dataChannel = mDataChannels.get(dataChannelId, null);
        if (dataChannel != null) {
            dataChannel.close();
            mDataChannels.remove(dataChannelId);
        } else {
            Log.d(TAG, "dataChannelClose() dataChannel is null");
        }
    }

    @Nullable
    public String iceConnectionStateString(PeerConnection.IceConnectionState iceConnectionState) {
        switch (iceConnectionState) {
            case NEW:
                return "new";
            case CHECKING:
                return "checking";
            case CONNECTED:
                return "connected";
            case COMPLETED:
                return "completed";
            case FAILED:
                return "failed";
            case DISCONNECTED:
                return "disconnected";
            case CLOSED:
                return "closed";
        }
        return null;
    }

    @Nullable
    public String signalingStateString(PeerConnection.SignalingState signalingState) {
        switch (signalingState) {
            case STABLE:
                return "stable";
            case HAVE_LOCAL_OFFER:
                return "have-local-offer";
            case HAVE_LOCAL_PRANSWER:
                return "have-local-pranswer";
            case HAVE_REMOTE_OFFER:
                return "have-remote-offer";
            case HAVE_REMOTE_PRANSWER:
                return "have-remote-pranswer";
            case CLOSED:
                return "closed";
        }
        return null;
    }

    @Nullable
    public String iceGatheringStateString(PeerConnection.IceGatheringState iceGatheringState) {
        switch (iceGatheringState) {
            case NEW:
                return "new";
            case GATHERING:
                return "gathering";
            case COMPLETE:
                return "complete";
        }
        return null;
    }
    @Nullable
    public String dataChannelStateString(DataChannel.State dataChannelState) {
        switch (dataChannelState) {
            case CONNECTING:
                return "connecting";
            case OPEN:
                return "open";
            case CLOSING:
                return "closing";
            case CLOSED:
                return "closed";
        }
        return null;
    }
}
