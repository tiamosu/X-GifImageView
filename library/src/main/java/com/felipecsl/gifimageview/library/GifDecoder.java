package com.felipecsl.gifimageview.library;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Reads frame data from a GIF image source and decodes it into individual frames
 * for animation purposes. Image data can be read from either and InputStream source
 * or a byte[].
 * <p>
 * This class is optimized for running animations with the frames, there
 * are no methods to get individual frame images, only to decode the next frame in the
 * animation sequence. Instead, it lowers its memory footprint by only housing the minimum
 * data necessary to decode the next frame in the animation sequence.
 * <p>
 * The animation must be manually moved forward using {@link #advance()} before requesting the next
 * frame. This method must also be called before you request the first frame or an error will
 * occur.
 * <p>
 * Implementation adapted from sample code published in Lyons. (2004). <em>Java for Programmers</em>,
 * republished under the MIT Open Source License
 */
@SuppressWarnings({"WeakerAccess", "unused"})
class GifDecoder {
    private static final String TAG = GifDecoder.class.getSimpleName();

    /**
     * File read status: No errors.
     */
    public static final int STATUS_OK = 0;
    /**
     * File read status: Error decoding file (may be partially decoded).
     */
    public static final int STATUS_FORMAT_ERROR = 1;
    /**
     * File read status: Unable to open source.
     */
    public static final int STATUS_OPEN_ERROR = 2;
    /**
     * Unable to fully decode the current frame.
     */
    public static final int STATUS_PARTIAL_DECODE = 3;
    /**
     * max decoder pixel stack size.
     */
    private static final int MAX_STACK_SIZE = 4096;

    /**
     * GIF Disposal Method meaning take no action.
     */
    private static final int DISPOSAL_UNSPECIFIED = 0;
    /**
     * GIF Disposal Method meaning leave canvas from previous frame.
     */
    private static final int DISPOSAL_NONE = 1;
    /**
     * GIF Disposal Method meaning clear canvas to background color.
     */
    private static final int DISPOSAL_BACKGROUND = 2;
    /**
     * GIF Disposal Method meaning clear canvas to frame before last.
     */
    private static final int DISPOSAL_PREVIOUS = 3;

    private static final int NULL_CODE = -1;

    private static final int INITIAL_FRAME_POINTER = -1;

    public static final int LOOP_FOREVER = -1;

    private static final int BYTES_PER_INTEGER = 4;

    // Global File Header values and parsing flags.
    // Active color table.
    private int[] mAct;
    // Private color table that can be modified if needed.
    private final int[] mPct = new int[256];

    // Raw GIF data from input source.
    private ByteBuffer mRawData;

    // Raw data read working array.
    private byte[] mBlock;

    // Temporary buffer for block reading. Reads 16k chunks from the native buffer for processing,
    // to greatly reduce JNI overhead.
    private static final int WORK_BUFFER_SIZE = 16384;
    @Nullable
    private byte[] mWorkBuffer;
    private int mWorkBufferSize = 0;
    private int mWorkBufferPosition = 0;

    private GifHeaderParser mParser;

    // LZW decoder working arrays.
    private short[] mPrefix;
    private byte[] mSuffix;
    private byte[] mPixelStack;
    private byte[] mMainPixels;
    private int[] mMainScratch;

    private int mFramePointer;
    private int mLoopIndex;
    private GifHeader mHeader;
    private BitmapProvider mBitmapProvider;
    private Bitmap mPreviousImage;
    private boolean mSavePrevious;
    private int mStatus;
    private int mSampleSize;
    private int mDownSampledHeight;
    private int mDownSampledWidth;
    private boolean mIsFirstFrameTransparent;

    /**
     * An interface that can be used to provide reused {@link android.graphics.Bitmap}s to avoid GCs
     * from constantly allocating {@link android.graphics.Bitmap}s for every frame.
     */
    interface BitmapProvider {
        /**
         * Returns an {@link Bitmap} with exactly the given dimensions and config.
         *
         * @param width  The width in pixels of the desired {@link android.graphics.Bitmap}.
         * @param height The height in pixels of the desired {@link android.graphics.Bitmap}.
         * @param config The {@link android.graphics.Bitmap.Config} of the desired {@link
         *               android.graphics.Bitmap}.
         */
        @NonNull
        Bitmap obtain(int width, int height, Bitmap.Config config);

        /**
         * Releases the given Bitmap back to the pool.
         */
        void release(Bitmap bitmap);

        /**
         * Returns a byte array used for decoding and generating the frame bitmap.
         *
         * @param size the size of the byte array to obtain
         */
        byte[] obtainByteArray(int size);

        /**
         * Releases the given byte array back to the pool.
         */
        void release(byte[] bytes);

        /**
         * Returns an int array used for decoding/generating the frame bitmaps.
         */
        int[] obtainIntArray(int size);

        /**
         * Release the given array back to the pool.
         */
        void release(int[] array);
    }

    public GifDecoder(BitmapProvider provider, GifHeader gifHeader, ByteBuffer rawData) {
        this(provider, gifHeader, rawData, 1);
    }

    public GifDecoder(BitmapProvider provider, GifHeader gifHeader, ByteBuffer rawData,
                      int sampleSize) {
        this(provider);
        setData(gifHeader, rawData, sampleSize);
    }

    public GifDecoder(BitmapProvider provider) {
        mBitmapProvider = provider;
        mHeader = new GifHeader();
    }

    public GifDecoder() {
        this(new SimpleBitmapProvider());
    }

    public int getWidth() {
        return mHeader.mWidth;
    }

    public int getHeight() {
        return mHeader.mHeight;
    }

    public ByteBuffer getData() {
        return mRawData;
    }

    /**
     * Returns the current status of the decoder.
     *
     * <p> Status will update per frame to allow the caller to tell whether or not the current frame
     * was decoded successfully and/or completely. Format and open failures persist across frames.
     * </p>
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Move the animation frame counter forward.
     *
     * @return boolean specifying if animation should continue or if loopCount has been fulfilled.
     */
    public boolean advance() {
        if (mHeader.mFrameCount <= 0) {
            return false;
        }
        if (mFramePointer == getFrameCount() - 1) {
            mLoopIndex++;
        }
        if (mHeader.mLoopCount != LOOP_FOREVER && mLoopIndex > mHeader.mLoopCount) {
            return false;
        }
        mFramePointer = (mFramePointer + 1) % mHeader.mFrameCount;
        return true;
    }

    /**
     * Gets display duration for specified frame.
     *
     * @param n int index of frame.
     * @return delay in milliseconds.
     */
    public int getDelay(int n) {
        int delay = -1;
        if ((n >= 0) && (n < mHeader.mFrameCount)) {
            delay = mHeader.mFrames.get(n).mDelay;
        }
        return delay;
    }

    /**
     * Gets display duration for the upcoming frame in ms.
     */
    public int getNextDelay() {
        if (mHeader.mFrameCount <= 0 || mFramePointer < 0) {
            return 0;
        }
        return getDelay(mFramePointer);
    }

    /**
     * Gets the number of frames read from file.
     *
     * @return frame count.
     */
    public int getFrameCount() {
        return mHeader.mFrameCount;
    }

    /**
     * Gets the current index of the animation frame, or -1 if animation hasn't not yet started.
     *
     * @return frame index.
     */
    public int getCurrentFrameIndex() {
        return mFramePointer;
    }

    /**
     * Sets the frame pointer to a specific frame
     *
     * @return boolean true if the move was successful
     */
    public boolean setFrameIndex(int frame) {
        if (frame < INITIAL_FRAME_POINTER || frame >= getFrameCount()) {
            return false;
        }
        mFramePointer = frame;
        return true;
    }

    /**
     * Resets the frame pointer to before the 0th frame, as if we'd never used this decoder to
     * decode any frames.
     */
    public void resetFrameIndex() {
        mFramePointer = INITIAL_FRAME_POINTER;
    }

    /**
     * Resets the loop index to the first loop.
     */
    public void resetLoopIndex() {
        mLoopIndex = 0;
    }

    /**
     * Gets the "Netscape" iteration count, if any. A count of 0 means repeat indefinitely.
     *
     * @return iteration count if one was specified, else 1.
     */
    public int getLoopCount() {
        return mHeader.mLoopCount;
    }

    /**
     * Gets the number of loops that have been shown.
     *
     * @return iteration count.
     */
    public int getLoopIndex() {
        return mLoopIndex;
    }

    /**
     * Returns an estimated byte size for this decoder based on the data provided to {@link
     * #setData(GifHeader, byte[])}, as well as internal buffers.
     */
    public int getByteSize() {
        return mRawData.limit() + mMainPixels.length + (mMainScratch.length * BYTES_PER_INTEGER);
    }

    /**
     * Get the next frame in the animation sequence.
     *
     * @return Bitmap representation of frame.
     */
    public synchronized Bitmap getNextFrame() {
        if (mHeader.mFrameCount <= 0 || mFramePointer < 0) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "unable to decode frame, frameCount=" + mHeader.mFrameCount
                        + " framePointer=" + mFramePointer);
            }
            mStatus = STATUS_FORMAT_ERROR;
        }
        if (mStatus == STATUS_FORMAT_ERROR || mStatus == STATUS_OPEN_ERROR) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unable to decode frame, status=" + mStatus);
            }
            return null;
        }
        mStatus = STATUS_OK;

        final GifFrame currentFrame = mHeader.mFrames.get(mFramePointer);
        GifFrame previousFrame = null;
        int previousIndex = mFramePointer - 1;
        if (previousIndex >= 0) {
            previousFrame = mHeader.mFrames.get(previousIndex);
        }

        // Set the appropriate color table.
        mAct = currentFrame.mLct != null ? currentFrame.mLct : mHeader.mGct;
        if (mAct == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No Valid Color Table for frame #" + mFramePointer);
            }
            // No color table defined.
            mStatus = STATUS_FORMAT_ERROR;
            return null;
        }

        // Reset the transparent pixel in the color table
        if (currentFrame.mTransparency) {
            // Prepare local copy of color table ("pct = act"), see #1068
            System.arraycopy(mAct, 0, mPct, 0, mAct.length);
            // Forget about act reference from shared header object, use copied version
            mAct = mPct;
            // Set transparent color if specified.
            mAct[currentFrame.mTransIndex] = 0;
        }

        // Transfer pixel data to image.
        return setPixels(currentFrame, previousFrame);
    }

    /**
     * Reads GIF image from stream.
     *
     * @param is containing GIF file.
     * @return read status code (0 = no errors).
     */
    public int read(InputStream is, int contentLength) {
        if (is == null) {
            mStatus = STATUS_OPEN_ERROR;
            return mStatus;
        }
        try {
            int capacity = (contentLength > 0) ? (contentLength + 4096) : 16384;
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(capacity);
            int read;
            byte[] data = new byte[16384];
            while ((read = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, read);
            }
            buffer.flush();

            read(buffer.toByteArray());
        } catch (IOException e) {
            Log.w(TAG, "Error reading data from stream", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing stream", e);
            }
        }
        return mStatus;
    }

    public void clear() {
        mHeader = null;
        if (mMainPixels != null) {
            mBitmapProvider.release(mMainPixels);
        }
        if (mMainScratch != null) {
            mBitmapProvider.release(mMainScratch);
        }
        if (mPreviousImage != null) {
            mBitmapProvider.release(mPreviousImage);
        }
        mPreviousImage = null;
        mRawData = null;
        mIsFirstFrameTransparent = false;
        if (mBlock != null) {
            mBitmapProvider.release(mBlock);
        }
        if (mWorkBuffer != null) {
            mBitmapProvider.release(mWorkBuffer);
        }
    }

    public synchronized void setData(GifHeader header, byte[] data) {
        setData(header, ByteBuffer.wrap(data));
    }

    public synchronized void setData(GifHeader header, ByteBuffer buffer) {
        setData(header, buffer, 1);
    }

    public synchronized void setData(GifHeader header, ByteBuffer buffer, int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sample size must be >=0, not: " + sampleSize);
        }
        // Make sure sample size is a power of 2.
        sampleSize = Integer.highestOneBit(sampleSize);
        this.mStatus = STATUS_OK;
        this.mHeader = header;
        mIsFirstFrameTransparent = false;
        mFramePointer = INITIAL_FRAME_POINTER;
        resetLoopIndex();
        // Initialize the raw data buffer.
        mRawData = buffer.asReadOnlyBuffer();
        mRawData.position(0);
        mRawData.order(ByteOrder.LITTLE_ENDIAN);

        // No point in specially saving an old frame if we're never going to use it.
        mSavePrevious = false;
        for (GifFrame frame : header.mFrames) {
            if (frame.mDispose == DISPOSAL_PREVIOUS) {
                mSavePrevious = true;
                break;
            }
        }

        mSampleSize = sampleSize;
        mDownSampledWidth = header.mWidth / sampleSize;
        mDownSampledHeight = header.mHeight / sampleSize;
        // Now that we know the size, init scratch arrays.
        // TODO Find a way to avoid this entirely or at least downSample it (either should be possible).
        mMainPixels = mBitmapProvider.obtainByteArray(header.mWidth * header.mHeight);
        mMainScratch = mBitmapProvider.obtainIntArray(mDownSampledWidth * mDownSampledHeight);
    }

    private GifHeaderParser getHeaderParser() {
        if (mParser == null) {
            mParser = new GifHeaderParser();
        }
        return mParser;
    }

    /**
     * Reads GIF image from byte array.
     *
     * @param data containing GIF file.
     * @return read status code (0 = no errors).
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized int read(byte[] data) {
        this.mHeader = getHeaderParser().setData(data).parseHeader();
        if (data != null) {
            setData(mHeader, data);
        }
        return mStatus;
    }

    /**
     * Creates new frame image from current data (and previous frames as specified by their
     * disposition codes).
     */
    private Bitmap setPixels(GifFrame currentFrame, GifFrame previousFrame) {
        // Final location of blended pixels.
        final int[] dest = mMainScratch;

        // clear all pixels when meet first frame
        if (previousFrame == null) {
            Arrays.fill(dest, 0);
        }

        // fill in starting image contents based on last image's dispose code
        if (previousFrame != null && previousFrame.mDispose > DISPOSAL_UNSPECIFIED) {
            // We don't need to do anything for DISPOSAL_NONE, if it has the correct pixels so will our
            // mainScratch and therefore so will our dest array.
            if (previousFrame.mDispose == DISPOSAL_BACKGROUND) {
                // Start with a canvas filled with the background color
                int c = 0;
                if (!currentFrame.mTransparency) {
                    c = mHeader.mBgColor;
                    if (currentFrame.mLct != null && mHeader.mBgIndex == currentFrame.mTransIndex) {
                        c = 0;
                    }
                } else if (mFramePointer == 0) {
                    // TODO: We should check and see if all individual pixels are replaced. If they are, the
                    // first frame isn't actually transparent. For now, it's simpler and safer to assume
                    // drawing a transparent background means the GIF contains transparency.
                    mIsFirstFrameTransparent = true;
                }
                fillRect(dest, previousFrame, c);
            } else if (previousFrame.mDispose == DISPOSAL_PREVIOUS) {
                if (mPreviousImage == null) {
                    fillRect(dest, previousFrame, 0);
                } else {
                    // Start with the previous frame
                    final int downSampledIH = previousFrame.mIh / mSampleSize;
                    final int downSampledIY = previousFrame.mIy / mSampleSize;
                    final int downSampledIW = previousFrame.mIw / mSampleSize;
                    final int downSampledIX = previousFrame.mIx / mSampleSize;
                    final int topLeft = downSampledIY * mDownSampledWidth + downSampledIX;
                    mPreviousImage.getPixels(dest, topLeft, mDownSampledWidth,
                            downSampledIX, downSampledIY, downSampledIW, downSampledIH);
                }
            }
        }

        // Decode pixels for this frame into the global pixels[] scratch.
        decodeBitmapData(currentFrame);

        final int downSampledIH = currentFrame.mIh / mSampleSize;
        final int downSampledIY = currentFrame.mIy / mSampleSize;
        final int downSampledIW = currentFrame.mIw / mSampleSize;
        final int downSampledIX = currentFrame.mIx / mSampleSize;
        // Copy each source line to the appropriate place in the destination.
        int pass = 1;
        int inc = 8;
        int iline = 0;
        boolean isFirstFrame = mFramePointer == 0;
        for (int i = 0; i < downSampledIH; i++) {
            int line = i;
            if (currentFrame.mInterlace) {
                if (iline >= downSampledIH) {
                    pass++;
                    switch (pass) {
                        case 2:
                            iline = 4;
                            break;
                        case 3:
                            iline = 2;
                            inc = 4;
                            break;
                        case 4:
                            iline = 1;
                            inc = 2;
                            break;
                        default:
                            break;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += downSampledIY;
            if (line < mDownSampledHeight) {
                final int k = line * mDownSampledWidth;
                // Start of line in dest.
                int dx = k + downSampledIX;
                // End of dest line.
                int dlim = dx + downSampledIW;
                if (k + mDownSampledWidth < dlim) {
                    // Past dest edge.
                    dlim = k + mDownSampledWidth;
                }
                // Start of line in source.
                int sx = i * mSampleSize * currentFrame.mIw;
                final int maxPositionInSource = sx + ((dlim - dx) * mSampleSize);
                while (dx < dlim) {
                    // Map color and insert in destination.
                    int averageColor;
                    if (mSampleSize == 1) {
                        final int currentColorIndex = ((int) mMainPixels[sx]) & 0x000000ff;
                        averageColor = mAct[currentColorIndex];
                    } else {
                        // TODO: This is substantially slower (up to 50ms per frame) than just grabbing the
                        // current color index above, even with a sample size of 1.
                        averageColor = averageColorsNear(sx, maxPositionInSource, currentFrame.mIw);
                    }
                    if (averageColor != 0) {
                        dest[dx] = averageColor;
                    } else if (!mIsFirstFrameTransparent && isFirstFrame) {
                        mIsFirstFrameTransparent = true;
                    }
                    sx += mSampleSize;
                    dx++;
                }
            }
        }

        // Copy pixels into previous image
        if (mSavePrevious && (currentFrame.mDispose == DISPOSAL_UNSPECIFIED
                || currentFrame.mDispose == DISPOSAL_NONE)) {
            if (mPreviousImage == null) {
                mPreviousImage = getNextBitmap();
            }
            mPreviousImage.setPixels(dest, 0, mDownSampledWidth,
                    0, 0, mDownSampledWidth, mDownSampledHeight);
        }

        // Set pixels for current image.
        final Bitmap result = getNextBitmap();
        result.setPixels(dest, 0, mDownSampledWidth, 0, 0, mDownSampledWidth, mDownSampledHeight);
        return result;
    }

    private void fillRect(int[] dest, GifFrame frame, int bgColor) {
        // The area used by the graphic must be restored to the background color.
        final int downSampledIH = frame.mIh / mSampleSize;
        final int downSampledIY = frame.mIy / mSampleSize;
        final int downSampledIW = frame.mIw / mSampleSize;
        final int downSampledIX = frame.mIx / mSampleSize;
        final int topLeft = downSampledIY * mDownSampledWidth + downSampledIX;
        final int bottomLeft = topLeft + downSampledIH * mDownSampledWidth;
        for (int left = topLeft; left < bottomLeft; left += mDownSampledWidth) {
            int right = left + downSampledIW;
            for (int pointer = left; pointer < right; pointer++) {
                dest[pointer] = bgColor;
            }
        }
    }

    private int averageColorsNear(int positionInMainPixels, int maxPositionInMainPixels,
                                  int currentFrameIw) {
        int alphaSum = 0;
        int redSum = 0;
        int greenSum = 0;
        int blueSum = 0;
        int totalAdded = 0;
        // Find the pixels in the current row.
        for (int i = positionInMainPixels;
             i < positionInMainPixels + mSampleSize && i < mMainPixels.length
                     && i < maxPositionInMainPixels; i++) {
            final int currentColorIndex = mMainPixels[i] & 0xff;
            final int currentColor = mAct[currentColorIndex];
            if (currentColor != 0) {
                alphaSum += currentColor >> 24 & 0x000000ff;
                redSum += currentColor >> 16 & 0x000000ff;
                greenSum += currentColor >> 8 & 0x000000ff;
                blueSum += currentColor & 0x000000ff;
                totalAdded++;
            }
        }
        // Find the pixels in the next row.
        for (int i = positionInMainPixels + currentFrameIw;
             i < positionInMainPixels + currentFrameIw + mSampleSize && i < mMainPixels.length
                     && i < maxPositionInMainPixels; i++) {
            final int currentColorIndex = mMainPixels[i] & 0xff;
            final int currentColor = mAct[currentColorIndex];
            if (currentColor != 0) {
                alphaSum += currentColor >> 24 & 0x000000ff;
                redSum += currentColor >> 16 & 0x000000ff;
                greenSum += currentColor >> 8 & 0x000000ff;
                blueSum += currentColor & 0x000000ff;
                totalAdded++;
            }
        }
        if (totalAdded == 0) {
            return 0;
        } else {
            return ((alphaSum / totalAdded) << 24)
                    | ((redSum / totalAdded) << 16)
                    | ((greenSum / totalAdded) << 8)
                    | (blueSum / totalAdded);
        }
    }

    /**
     * Decodes LZW image data into pixel array. Adapted from John Cristy's BitmapMagick.
     */
    private void decodeBitmapData(GifFrame frame) {
        mWorkBufferSize = 0;
        mWorkBufferPosition = 0;
        if (frame != null) {
            // Jump to the frame start position.
            mRawData.position(frame.mBufferFrameStart);
        }

        final int nPix = (frame == null) ? mHeader.mWidth * mHeader.mHeight : frame.mIw * frame.mIh;
        int available, clear, codeMask, codeSize, endOfInformation, inCode, oldCode, bits, code, count,
                i, datum, dataSize, first, top, bi, pi;

        if (mMainPixels == null || mMainPixels.length < nPix) {
            // Allocate new pixel array.
            mMainPixels = mBitmapProvider.obtainByteArray(nPix);
        }
        if (mPrefix == null) {
            mPrefix = new short[MAX_STACK_SIZE];
        }
        if (mSuffix == null) {
            mSuffix = new byte[MAX_STACK_SIZE];
        }
        if (mPixelStack == null) {
            mPixelStack = new byte[MAX_STACK_SIZE + 1];
        }

        // Initialize GIF data stream decoder.
        dataSize = readByte();
        clear = 1 << dataSize;
        endOfInformation = clear + 1;
        available = clear + 2;
        oldCode = NULL_CODE;
        codeSize = dataSize + 1;
        codeMask = (1 << codeSize) - 1;
        for (code = 0; code < clear; code++) {
            // XXX ArrayIndexOutOfBoundsException.
            mPrefix[code] = 0;
            mSuffix[code] = (byte) code;
        }

        // Decode GIF pixel stream.
        datum = bits = count = first = top = pi = bi = 0;
        for (i = 0; i < nPix; ) {
            // Load bytes until there are enough bits for a code.
            if (count == 0) {
                // Read a new data block.
                count = readBlock();
                if (count <= 0) {
                    mStatus = STATUS_PARTIAL_DECODE;
                    break;
                }
                bi = 0;
            }

            datum += (((int) mBlock[bi]) & 0xff) << bits;
            bits += 8;
            bi++;
            count--;

            while (bits >= codeSize) {
                // Get the next code.
                code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                // Interpret the code.
                if (code == clear) {
                    // Reset decoder.
                    codeSize = dataSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = NULL_CODE;
                    continue;
                }
                if (code > available) {
                    mStatus = STATUS_PARTIAL_DECODE;
                    break;
                }
                if (code == endOfInformation) {
                    break;
                }
                if (oldCode == NULL_CODE) {
                    mPixelStack[top++] = mSuffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }
                inCode = code;
                if (code >= available) {
                    mPixelStack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code >= clear) {
                    mPixelStack[top++] = mSuffix[code];
                    code = mPrefix[code];
                }
                first = ((int) mSuffix[code]) & 0xff;
                mPixelStack[top++] = (byte) first;

                // Add a new string to the string table.
                if (available < MAX_STACK_SIZE) {
                    mPrefix[available] = (short) oldCode;
                    mSuffix[available] = (byte) first;
                    available++;
                    if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inCode;

                while (top > 0) {
                    // Pop a pixel off the pixel stack.
                    mMainPixels[pi++] = mPixelStack[--top];
                    i++;
                }
            }
        }

        // Clear missing pixels.
        for (i = pi; i < nPix; i++) {
            mMainPixels[i] = 0;
        }
    }

    /**
     * Reads the next chunk for the intermediate work buffer.
     */
    private void readChunkIfNeeded() {
        if (mWorkBufferSize > mWorkBufferPosition) {
            return;
        }
        if (mWorkBuffer == null) {
            mWorkBuffer = mBitmapProvider.obtainByteArray(WORK_BUFFER_SIZE);
        }
        mWorkBufferPosition = 0;
        mWorkBufferSize = Math.min(mRawData.remaining(), WORK_BUFFER_SIZE);
        mRawData.get(mWorkBuffer, 0, mWorkBufferSize);
    }

    /**
     * Reads a single byte from the input stream.
     */
    private int readByte() {
        try {
            readChunkIfNeeded();
            if (mWorkBuffer != null) {
                return mWorkBuffer[mWorkBufferPosition++] & 0xFF;
            }
        } catch (Exception e) {
            mStatus = STATUS_FORMAT_ERROR;
        }
        return 0;
    }

    /**
     * Reads next variable length block from input.
     *
     * @return number of bytes stored in "buffer".
     */
    private int readBlock() {
        int blockSize = readByte();
        if (blockSize <= 0) {
            return blockSize;
        }
        try {
            if (mBlock == null) {
                mBlock = mBitmapProvider.obtainByteArray(255);
            }
            final int remaining = mWorkBufferSize - mWorkBufferPosition;
            if (remaining >= blockSize) {
                // Block can be read from the current work buffer.
                if (mWorkBuffer != null) {
                    System.arraycopy(mWorkBuffer, mWorkBufferPosition, mBlock, 0, blockSize);
                }
                mWorkBufferPosition += blockSize;
            } else if (mRawData.remaining() + remaining >= blockSize) {
                // Block can be read in two passes.
                if (mWorkBuffer != null) {
                    System.arraycopy(mWorkBuffer, mWorkBufferPosition, mBlock, 0, remaining);
                }
                mWorkBufferPosition = mWorkBufferSize;
                readChunkIfNeeded();
                final int secondHalfRemaining = blockSize - remaining;
                if (mWorkBuffer != null) {
                    System.arraycopy(mWorkBuffer, 0, mBlock, remaining, secondHalfRemaining);
                }
                mWorkBufferPosition += secondHalfRemaining;
            } else {
                mStatus = STATUS_FORMAT_ERROR;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error Reading Block", e);
            mStatus = STATUS_FORMAT_ERROR;
        }
        return blockSize;
    }

    private Bitmap getNextBitmap() {
        final Bitmap.Config config = mIsFirstFrameTransparent
                ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        final Bitmap result = mBitmapProvider.obtain(mDownSampledWidth, mDownSampledHeight, config);
        setAlpha(result);
        return result;
    }

    private static void setAlpha(Bitmap bitmap) {
        bitmap.setHasAlpha(true);
    }
}