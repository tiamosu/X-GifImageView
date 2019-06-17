package com.felipecsl.gifimageview.library;

import android.util.Log;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A class responsible for creating {@link GifHeader}s from data
 * representing animated gifs.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class GifHeaderParser {
    private static final String TAG = "GifHeaderParser";

    // The minimum frame delay in hundredths of a second.
    static final int MIN_FRAME_DELAY = 2;
    // The default frame delay in hundredths of a second for GIFs with frame delays less than the
    // minimum.
    static final int DEFAULT_FRAME_DELAY = 10;

    private static final int MAX_BLOCK_SIZE = 256;
    // Raw data read working array.
    private final byte[] block = new byte[MAX_BLOCK_SIZE];

    private ByteBuffer rawData;
    private GifHeader header;
    private int blockSize = 0;

    public GifHeaderParser setData(ByteBuffer data) {
        reset();
        rawData = data.asReadOnlyBuffer();
        rawData.position(0);
        rawData.order(ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public GifHeaderParser setData(byte[] data) {
        if (data != null) {
            setData(ByteBuffer.wrap(data));
        } else {
            rawData = null;
            header.mStatus = GifDecoder.STATUS_OPEN_ERROR;
        }
        return this;
    }

    public void clear() {
        rawData = null;
        header = null;
    }

    private void reset() {
        rawData = null;
        Arrays.fill(block, (byte) 0);
        header = new GifHeader();
        blockSize = 0;
    }

    public GifHeader parseHeader() {
        if (rawData == null) {
            throw new IllegalStateException("You must call setData() before parseHeader()");
        }
        if (err()) {
            return header;
        }

        readHeader();
        if (!err()) {
            readContents();
            if (header.mFrameCount < 0) {
                header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
            }
        }
        return header;
    }

    /**
     * Determines if the GIF is animated by trying to read in the first 2 frames
     * This method reparses the data even if the header has already been read.
     */
    public boolean isAnimated() {
        readHeader();
        if (!err()) {
            readContents(2 /* maxFrames */);
        }
        return header.mFrameCount > 1;
    }

    /**
     * Main file parser. Reads GIF content blocks.
     */
    private void readContents() {
        readContents(Integer.MAX_VALUE /* maxFrames */);
    }

    /**
     * Main file parser. Reads GIF content blocks. Stops after reading maxFrames
     */
    private void readContents(int maxFrames) {
        // Read GIF file content blocks.
        boolean done = false;
        while (!(done || err() || header.mFrameCount > maxFrames)) {
            int code = read();
            switch (code) {
                // Image separator.
                case 0x2C:
                    // The graphics control extension is optional, but will always come first if it exists.
                    // If one did
                    // exist, there will be a non-null current frame which we should use. However if one
                    // did not exist,
                    // the current frame will be null and we must create it here. See issue #134.
                    if (header.mCurrentFrame == null) {
                        header.mCurrentFrame = new GifFrame();
                    }
                    readBitmap();
                    break;
                // Extension.
                case 0x21:
                    code = read();
                    switch (code) {
                        // Graphics control extension.
                        case 0xf9:
                            // Start a new frame.
                            header.mCurrentFrame = new GifFrame();
                            readGraphicControlExt();
                            break;
                        // Application extension.
                        case 0xff:
                            readBlock();
                            final StringBuilder builder = new StringBuilder();
                            for (int i = 0; i < 11; i++) {
                                builder.append((char) block[i]);
                            }
                            if ("NETSCAPE2.0".equals(builder.toString())) {
                                readNetscapeExt();
                            } else {
                                // Don't care.
                                skip();
                            }
                            break;
                        // Comment extension.
                        case 0xfe:
                            skip();
                            break;
                        // Plain text extension.
                        case 0x01:
                            skip();
                            break;
                        // Uninteresting extension.
                        default:
                            skip();
                    }
                    break;
                // Terminator.
                case 0x3b:
                    done = true;
                    break;
                // Bad byte, but keep going and see what happens break;
                case 0x00:
                default:
                    header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
            }
        }
    }

    /**
     * Reads Graphics Control Extension values.
     */
    private void readGraphicControlExt() {
        // Block size.
        read();
        // Packed fields.
        final int packed = read();
        // Disposal method.
        header.mCurrentFrame.mDispose = (packed & 0x1c) >> 2;
        if (header.mCurrentFrame.mDispose == 0) {
            // Elect to keep old image if discretionary.
            header.mCurrentFrame.mDispose = 1;
        }
        header.mCurrentFrame.mTransparency = (packed & 1) != 0;
        // Delay in milliseconds.
        int delayInHundredthsOfASecond = readShort();
        // TODO: consider allowing -1 to indicate show forever.
        if (delayInHundredthsOfASecond < MIN_FRAME_DELAY) {
            delayInHundredthsOfASecond = DEFAULT_FRAME_DELAY;
        }
        header.mCurrentFrame.mDelay = delayInHundredthsOfASecond * 10;
        // Transparent color index
        header.mCurrentFrame.mTransIndex = read();
        // Block terminator
        read();
    }

    /**
     * Reads next frame image.
     */
    private void readBitmap() {
        // (sub)image position & size.
        header.mCurrentFrame.mIx = readShort();
        header.mCurrentFrame.mIy = readShort();
        header.mCurrentFrame.mIw = readShort();
        header.mCurrentFrame.mIh = readShort();

        final int packed = read();
        // 1 - local color table flag interlace
        final boolean lctFlag = (packed & 0x80) != 0;
        final int lctSize = (int) Math.pow(2, (packed & 0x07) + 1);
        // 3 - sort flag
        // 4-5 - reserved lctSize = 2 << (packed & 7); // 6-8 - local color
        // table size
        header.mCurrentFrame.mInterlace = (packed & 0x40) != 0;
        if (lctFlag) {
            // Read table.
            header.mCurrentFrame.mLct = readColorTable(lctSize);
        } else {
            // No local color table.
            header.mCurrentFrame.mLct = null;
        }

        // Save this as the decoding position pointer.
        header.mCurrentFrame.mBufferFrameStart = rawData.position();

        // False decode pixel data to advance buffer.
        skipImageData();

        if (err()) {
            return;
        }

        header.mFrameCount++;
        // Add image to frame.
        header.mFrames.add(header.mCurrentFrame);
    }

    /**
     * Reads Netscape extension to obtain iteration count.
     */
    private void readNetscapeExt() {
        do {
            readBlock();
            if (block[0] == 1) {
                // Loop count sub-block.
                final int b1 = ((int) block[1]) & 0xff;
                final int b2 = ((int) block[2]) & 0xff;
                header.mLoopCount = (b2 << 8) | b1;
                if (header.mLoopCount == 0) {
                    header.mLoopCount = GifDecoder.LOOP_FOREVER;
                }
            }
        } while ((blockSize > 0) && !err());
    }

    /**
     * Reads GIF file header information.
     */
    private void readHeader() {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            builder.append((char) read());
        }
        if (!builder.toString().startsWith("GIF")) {
            header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
            return;
        }
        readLSD();
        if (header.mGctFlag && !err()) {
            header.mGct = readColorTable(header.mGctSize);
            header.mBgColor = header.mGct[header.mBgIndex];
        }
    }

    /**
     * Reads Logical Screen Descriptor.
     */
    private void readLSD() {
        // Logical screen size.
        header.mWidth = readShort();
        header.mHeight = readShort();
        // Packed fields
        int packed = read();
        // 1 : global color table flag.
        header.mGctFlag = (packed & 0x80) != 0;
        // 2-4 : color resolution.
        // 5 : gct sort flag.
        // 6-8 : gct size.
        header.mGctSize = 2 << (packed & 7);
        // Background color index.
        header.mBgIndex = read();
        // Pixel aspect ratio
        header.mPixelAspect = read();
    }

    /**
     * Reads color table as 256 RGB integer values.
     *
     * @param colors int number of colors to read.
     * @return int array containing 256 colors (packed ARGB with full alpha).
     */
    private int[] readColorTable(int colors) {
        final int bytes = 3 * colors;
        final byte[] c = new byte[bytes];
        int[] tab = null;

        try {
            rawData.get(c);
            // TODO: what bounds checks are we avoiding if we know the number of colors?
            // Max size to avoid bounds checks.
            tab = new int[MAX_BLOCK_SIZE];
            int i = 0;
            int j = 0;
            while (i < colors) {
                int r = ((int) c[j++]) & 0xff;
                int g = ((int) c[j++]) & 0xff;
                int b = ((int) c[j++]) & 0xff;
                tab[i++] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        } catch (BufferUnderflowException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Format Error Reading Color Table", e);
            }
            header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
        }
        return tab;
    }

    /**
     * Skips LZW image data for a single frame to advance buffer.
     */
    private void skipImageData() {
        // lzwMinCodeSize
        read();
        // data sub-blocks
        skip();
    }

    /**
     * Skips variable length blocks up to and including next zero length block.
     */
    private void skip() {
        try {
            int blockSize;
            do {
                blockSize = read();
                rawData.position(rawData.position() + blockSize);
            } while (blockSize > 0);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * Reads next variable length block from input.
     *
     * @return number of bytes stored in "buffer"
     */
    private int readBlock() {
        blockSize = read();
        int n = 0;
        if (blockSize > 0) {
            int count = 0;
            try {
                while (n < blockSize) {
                    count = blockSize - n;
                    rawData.get(block, n, count);

                    n += count;
                }
            } catch (Exception e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Error Reading Block n: " + n
                            + " count: " + count + " blockSize: " + blockSize, e);
                }
                header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
            }
        }
        return n;
    }

    /**
     * Reads a single byte from the input stream.
     */
    private int read() {
        int curByte = 0;
        try {
            curByte = rawData.get() & 0xFF;
        } catch (Exception e) {
            header.mStatus = GifDecoder.STATUS_FORMAT_ERROR;
        }
        return curByte;
    }

    /**
     * Reads next 16-bit value, LSB first.
     */
    private int readShort() {
        // Read 16-bit value.
        return rawData.getShort();
    }

    private boolean err() {
        return header.mStatus != GifDecoder.STATUS_OK;
    }
}