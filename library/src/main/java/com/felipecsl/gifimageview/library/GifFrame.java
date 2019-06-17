package com.felipecsl.gifimageview.library;

/**
 * Inner model class housing metadata for each frame.
 */
@SuppressWarnings("WeakerAccess")
class GifFrame {
    public int mIx, mIy, mIw, mIh;
    /**
     * Control Flag.
     */
    public boolean mInterlace;
    /**
     * Control Flag.
     */
    public boolean mTransparency;
    /**
     * Disposal Method.
     */
    public int mDispose;
    /**
     * Transparency Index.
     */
    public int mTransIndex;
    /**
     * Delay, in ms, to next frame.
     */
    public int mDelay;
    /**
     * Index in the raw buffer where we need to start reading to decode.
     */
    public int mBufferFrameStart;
    /**
     * Local Color Table.
     */
    public int[] mLct;
}