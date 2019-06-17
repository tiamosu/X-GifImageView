package com.felipecsl.gifimageview.library;

import java.util.ArrayList;
import java.util.List;

/**
 * A header object containing the number of frames in an animated GIF image as well as basic
 * metadata like width and height that can be used to decode each individual frame of the GIF. Can
 * be shared by one or more {@link GifDecoder}s to play the same animated GIF in multiple views.
 */
@SuppressWarnings("WeakerAccess")
public class GifHeader {
    public int[] mGct = null;
    public int mStatus = GifDecoder.STATUS_OK;
    public int mFrameCount = 0;

    public GifFrame mCurrentFrame;
    public List<GifFrame> mFrames = new ArrayList<>();
    // Logical screen size.
    // Full image width.
    public int mWidth;
    // Full image height.
    public int mHeight;

    // 1 : global color table flag.
    public boolean mGctFlag;
    // 2-4 : color resolution.
    // 5 : gct sort flag.
    // 6-8 : gct size.
    public int mGctSize;
    // Background color index.
    public int mBgIndex;
    // Pixel aspect ratio.
    public int mPixelAspect;
    //TODO: this is set both during reading the header and while decoding frames...
    public int mBgColor;
    public int mLoopCount = 0;
}