package com.polarxiong.videotoimages;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by zhantong on 16/5/12.
 */
public class VideoToFrames implements Runnable {
    private static final String TAG = "VideoToFrames";
    private static final boolean VERBOSE = false;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;


    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private String OUTPUT_DIR;
    private boolean stopDecode = false;

    private String videoFilePath;
    private Throwable throwable;
    private Thread childThread;

    private Callback callback;

    public interface Callback {
        void onFinishDecode();

        void onDecodeFrame(int index);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setEnqueue(LinkedBlockingQueue<byte[]> queue) {
        mQueue = queue;
    }

    public void setSaveFrames(String dir, OutputImageFormat imageFormat) throws IOException {
        outputImageFormat = imageFormat;
        File theDir = new File(dir);
        if (!theDir.exists()) {
            theDir.mkdirs();
        } else if (!theDir.isDirectory()) {
            throw new IOException("Not a directory");
        }
        OUTPUT_DIR = theDir.getAbsolutePath() + "/";
    }

    public void stopDecode() {
        stopDecode = true;
    }

    public void decode(String videoFilePath) throws Throwable {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void run() {
        try {
            videoDecode(videoFilePath);
        } catch (Throwable t) {
            throwable = t;
        }
    }

    public void videoDecode(String videoFilePath) throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            File videoFile = new File(videoFilePath);
            extractor = new MediaExtractor();
            extractor.setDataSource(videoFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + videoFilePath);
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                Log.i(TAG, "set decode color format to type " + decodeColorFormat);
            } else {
                Log.i(TAG, "unable to set decode color format, color format type " + decodeColorFormat + " not supported");
            }
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        System.out.print("supported color format: ");
        for (int c : caps.colorFormats) {
            System.out.print(c + "\t");
        }
        System.out.println();
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.configure(mediaFormat, null, null, 0);
        decoder.start();
        final int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int outputFrameCount = 0;
        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);
                if (doRender) {
                    outputFrameCount++;
                    if (callback != null) {
                        callback.onDecodeFrame(outputFrameCount);
                    }
                    Image image = decoder.getOutputImage(outputBufferId);
                    //System.out.println("image format: " + image.getFormat());

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] arr = new byte[buffer.remaining()];
                    buffer.get(arr);
                    if (mQueue != null) {
                        try {
                            mQueue.put(arr);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    if (outputImageFormat != null) {
                        String fileName;
                        switch (outputImageFormat) {
                            case I420:
                                fileName = OUTPUT_DIR + String.format("frame_%05d_I420_%dx%d.yuv", outputFrameCount, width, height);
                                dumpFile(fileName, getDataFromImage(image, COLOR_FormatI420));
                                break;
                            case NV21:
                                fileName = OUTPUT_DIR + String.format("frame_%05d_NV21_%dx%d.yuv", outputFrameCount, width, height);
                                dumpFile(fileName, getDataFromImage(image, COLOR_FormatNV21));
                                break;
                            case JPEG:
                                fileName = OUTPUT_DIR + String.format("frame_%05d.jpg", outputFrameCount);
                                compressToJpeg(fileName, image);
                                break;
                        }
                    }
                    image.close();
                    decoder.releaseOutputBuffer(outputBufferId, true);
                }
            }
        }
        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 " + "and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        if (VERBOSE) Log.v(TAG, "get data from " + planes.length + " planes");
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            if (VERBOSE) {
                Log.v(TAG, "pixelStride " + pixelStride);
                Log.v(TAG, "rowStride " + rowStride);
                Log.v(TAG, "width " + width);
                Log.v(TAG, "height " + height);
                Log.v(TAG, "buffer size " + buffer.remaining());
            }
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
            if (VERBOSE) Log.v(TAG, "Finished reading data from plane " + i);
        }
        return data;
    }

    private static void dumpFile(String fileName, byte[] data) {
        FileOutputStream outStream;
        try {
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create output file " + fileName, ioe);
        }
        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

    private void compressToJpeg(String fileName, Image image) {
        FileOutputStream outStream;
        try {
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create output file " + fileName, ioe);
        }
        Rect rect = image.getCropRect();
        YuvImage yuvImage = new YuvImage(getDataFromImage(image, COLOR_FormatNV21), ImageFormat.NV21, rect.width(), rect.height(), null);
        yuvImage.compressToJpeg(rect, 100, outStream);
    }
}
