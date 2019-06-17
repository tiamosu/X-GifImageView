package com.felipecsl.gifimageview.app;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.renderscript.Allocation;
import androidx.renderscript.Element;
import androidx.renderscript.RenderScript;
import androidx.renderscript.ScriptIntrinsicBlur;

@SuppressWarnings("WeakerAccess")
public class Blur {
    private static final float BLUR_RADIUS = 25f;

    private final RenderScript mRenderScript;
    private ScriptIntrinsicBlur mScript;
    private Allocation mInput;
    private Allocation mOutput;
    private boolean mConfigured = false;
    private Bitmap mTmp;
    private int[] mPixels;

    public static Blur newInstance(Context context) {
        return new Blur(context);
    }

    private Blur(Context context) {
        mRenderScript = RenderScript.create(context);
    }

    public Bitmap blur(Bitmap image) {
        if (image == null) {
            return null;
        }

        image = RGB565toARGB888(image);
        if (!mConfigured) {
            mInput = Allocation.createFromBitmap(mRenderScript, image);
            mOutput = Allocation.createTyped(mRenderScript, mInput.getType());
            mScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript));
            mScript.setRadius(BLUR_RADIUS);
            mConfigured = true;
        } else {
            mInput.copyFrom(image);
        }

        mScript.setInput(mInput);
        mScript.forEach(mOutput);
        mOutput.copyTo(image);

        return image;
    }

    private Bitmap RGB565toARGB888(Bitmap img) {
        int numPixels = img.getWidth() * img.getHeight();

        //Create a Bitmap of the appropriate format.
        if (mTmp == null) {
            mTmp = Bitmap.createBitmap(img.getWidth(), img.getHeight(), Bitmap.Config.ARGB_8888);
            mPixels = new int[numPixels];
        }

        //Get JPEG pixels.  Each int is the color values for one pixel.
        img.getPixels(mPixels, 0, img.getWidth(), 0, 0, img.getWidth(), img.getHeight());

        //Set RGB pixels.
        mTmp.setPixels(mPixels, 0, mTmp.getWidth(), 0, 0, mTmp.getWidth(), mTmp.getHeight());

        return mTmp;
    }
}
