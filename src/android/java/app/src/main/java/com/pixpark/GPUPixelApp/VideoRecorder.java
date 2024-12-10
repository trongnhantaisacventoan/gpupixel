package com.pixpark.GPUPixelApp;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.pixpark.gpupixel.GPUPixel;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoRecorder implements GPUPixel.RawOutputCallback {
    private static final String TAG = "VideoRecorder";

    private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;

    private MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;

    private final MediaMuxer mediaMuxer;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;

    private int videoTrackIndex = -1;
    private int audioTrackIndex;

    private volatile boolean muxerStarted = false;


    private boolean isRunning = true;

    private boolean isAudioRunning = false;

    private ByteBuffer[] videoInputBuffers;
    private ByteBuffer[] videoOutputBuffers;
    private ByteBuffer[] audioInputBuffers;
    private ByteBuffer[] audioOutputBuffers;

    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private static final int AUDIO_SAMPLE_RATE = 16000;

    private AudioRecord audioRecord;

    private boolean enableAudio;

    @SuppressLint("MissingPermission")
    VideoRecorder(String outputFile, boolean withAudio) throws IOException {
        this.enableAudio = withAudio;
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        if (withAudio) {
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());

            audioThreadHandler.post(()->{
                int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
                audioRecord.startRecording();
            });
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        mediaMuxer = new MediaMuxer(outputFile,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        audioTrackIndex = withAudio ? -1 : 0;
    }

    @Override
    public void onRawOutput(byte[] data, int width, int height, int ts) {
        if (!isRunning)
            return;

        renderThreadHandler.post(() -> {
            if (videoEncoder == null) try {
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
                // Set some properties.  Failing to specify some of these can cause the MediaCodec
                // configure() call to throw an unhelpful exception.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
                videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();
                videoInputBuffers = videoEncoder.getInputBuffers();
                videoOutputBuffers = videoEncoder.getOutputBuffers();
            } catch (IOException exception) {
                Log.wtf(TAG, exception);
            }

            // convert argb to yuv420 or nv21

            int bufferIndex = videoEncoder.dequeueInputBuffer(10000);
            if (bufferIndex >= 0) {
                ByteBuffer buffer = videoInputBuffers[bufferIndex];
                buffer.clear();
                buffer.put(data);
                long presentationTimeUs = System.nanoTime() / 1000;
                videoEncoder.queueInputBuffer(bufferIndex, 0, data.length, presentationTimeUs, 0);
            }
            drainVideo();
        });

        if(!isAudioRunning && enableAudio){
            audioThreadHandler.post(()->{
                encodeAudio();
            });
            isAudioRunning = true;
        }
    }

    private void drainVideo() {
        if (videoBufferInfo == null)
            videoBufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                videoOutputBuffers = videoEncoder.getOutputBuffers();
                Log.w(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = videoEncoder.getOutputFormat();

                Log.w(TAG, "encoder output format changed: " + newFormat);
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                if (audioTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = videoOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(videoBufferInfo.offset);
                    encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                    if (muxerStarted)
                        mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo);
                    isRunning = isRunning && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    videoEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }


    private void drainAudio() {
        if (audioBufferInfo == null)
            audioBufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                audioOutputBuffers = audioEncoder.getOutputBuffers();
                Log.w(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = audioEncoder.getOutputFormat();
                Log.w(TAG, "encoder output format changed: " + newFormat);
                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                if (videoTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = audioOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(audioBufferInfo.offset);
                    encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);
                    if (muxerStarted)
                        mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                    isRunning = isRunning && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    audioEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private void encodeAudio() {
        if (audioEncoder == null) try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128 * 1024);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioInputBuffers = audioEncoder.getInputBuffers();
            audioOutputBuffers = audioEncoder.getOutputBuffers();
        } catch (IOException exception) {
            Log.wtf(TAG, exception);
        }
        while (isRunning) {
            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = audioInputBuffers[inputBufferIndex];
                inputBuffer.clear();
                int bytesRead = audioRecord.read(inputBuffer, inputBuffer.capacity());
                if (bytesRead > 0) {
                    audioEncoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, System.nanoTime() / 1000, 0);
                }
            }
            drainAudio();
        }
    }

    /**
     * Release all resources. All already posted frames will be rendered first.
     */
    void release() {
        isRunning = false;
        if (audioThreadHandler != null)
            audioThreadHandler.post(() -> {
                if(audioRecord != null){
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                }

                if (audioEncoder != null) {
                    audioEncoder.stop();
                    audioEncoder.release();
                }
                audioThread.quit();
            });
        renderThreadHandler.post(() -> {
            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
            }
            mediaMuxer.stop();
            mediaMuxer.release();
            renderThread.quit();
        });
    }
}
