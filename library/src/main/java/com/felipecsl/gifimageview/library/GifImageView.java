package com.felipecsl.gifimageview.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

@SuppressWarnings("unused")
public class GifImageView extends ImageView implements Runnable {
    private static final String TAG = "GifDecoderView";
    private GifDecoder mGifDecoder;
    private Bitmap mTmpBitmap;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mAnimating;
    private boolean mRenderFrame;
    private boolean mShouldClear;
    private Thread mAnimationThread;
    private long mFramesDisplayDuration = -1L;
    private boolean mIsLoadOnlyOnce;

    private OnFrameAvailable mOnFrameAvailable = null;
    private OnAnimationStop mOnAnimationStop = null;
    private OnAnimationStart mOnAnimationStart = null;

    private final Runnable mUpdateResults = new Runnable() {
        @Override
        public void run() {
            if (mTmpBitmap != null && !mTmpBitmap.isRecycled()) {
                setImageBitmap(mTmpBitmap);
            }
        }
    };

    private final Runnable mCleanupRunnable = new Runnable() {
        @Override
        public void run() {
            mTmpBitmap = null;
            mGifDecoder = null;
            mAnimationThread = null;
            mShouldClear = false;
        }
    };

    public GifImageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public GifImageView(final Context context) {
        super(context);
    }

    public void setBytes(final byte[] bytes) {
        setBytes(bytes, false);
    }

    public void setBytes(final byte[] bytes, boolean isLoadOnlyOnce) {
        mGifDecoder = new GifDecoder();
        mIsLoadOnlyOnce = isLoadOnlyOnce;

        try {
            mGifDecoder.read(bytes);
        } catch (final Exception e) {
            mGifDecoder = null;
            Log.e(TAG, e.getMessage(), e);
            return;
        }

        if (mAnimating) {
            startAnimationThread();
        } else {
            gotoFrame(0);
        }
    }

    public long getFramesDisplayDuration() {
        return mFramesDisplayDuration;
    }

    /**
     * Sets custom display duration in milliseconds for the all frames. Should be called before {@link
     * #startAnimation()}
     *
     * @param framesDisplayDuration Duration in milliseconds. Default value = -1, this property will
     *                              be ignored and default delay from gif file will be used.
     */
    public void setFramesDisplayDuration(long framesDisplayDuration) {
        this.mFramesDisplayDuration = framesDisplayDuration;
    }

    public void startAnimation() {
        mAnimating = true;
        startAnimationThread();
    }

    public boolean isAnimating() {
        return mAnimating;
    }

    public void stopAnimation() {
        mAnimating = false;

        if (mAnimationThread != null) {
            mAnimationThread.interrupt();
            mAnimationThread = null;
        }
    }

    public void gotoFrame(int frame) {
        if (mGifDecoder.getCurrentFrameIndex() == frame) {
            return;
        }
        if (mGifDecoder.setFrameIndex(frame - 1) && !mAnimating) {
            mRenderFrame = true;
            startAnimationThread();
        }
    }

    public void resetAnimation() {
        mGifDecoder.resetLoopIndex();
        gotoFrame(0);
    }

    public void clear() {
        mAnimating = false;
        mRenderFrame = false;
        mShouldClear = true;
        stopAnimation();
        mHandler.post(mCleanupRunnable);
    }

    private boolean canStart() {
        return (mAnimating || mRenderFrame) && mGifDecoder != null && mAnimationThread == null;
    }

    public int getGifWidth() {
        return mGifDecoder.getWidth();
    }

    /**
     * Gets the number of frames read from file.
     *
     * @return frame count.
     */
    public int getFrameCount() {
        return mGifDecoder.getFrameCount();
    }

    public int getGifHeight() {
        return mGifDecoder.getHeight();
    }

    @Override
    public void run() {
        if (mOnAnimationStart != null) {
            mOnAnimationStart.onAnimationStart();
        }

        int frameCount = mGifDecoder.getFrameCount();
        do {
            if (!mAnimating && !mRenderFrame) {
                break;
            }
            final boolean advance = mGifDecoder.advance();

            //milliseconds spent on frame decode
            long frameDecodeTime = 0;
            try {
                final long before = System.nanoTime();
                mTmpBitmap = mGifDecoder.getNextFrame();
                if (mOnFrameAvailable != null) {
                    mTmpBitmap = mOnFrameAvailable.onFrameAvailable(mTmpBitmap);
                }
                frameDecodeTime = (System.nanoTime() - before) / 1000000;
                mHandler.post(mUpdateResults);
            } catch (final ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
                Log.w(TAG, e);
            }

            mRenderFrame = false;
            if (!mAnimating || !advance) {
                mAnimating = false;
                break;
            }
            try {
                int delay = mGifDecoder.getNextDelay();
                // Sleep for frame duration minus time already spent on frame decode
                // Actually we need next frame decode duration here,
                // but I use previous frame time to make code more readable
                delay -= frameDecodeTime;
                if (delay > 0) {
                    Thread.sleep(mFramesDisplayDuration > 0 ? mFramesDisplayDuration : delay);
                }
            } catch (final InterruptedException e) {
                // suppress exception
            }
            if (isAnimating() && mIsLoadOnlyOnce) {
                frameCount--;
                if (frameCount < 0) {
                    mAnimating = false;
                }
            }
        } while (mAnimating);

        if (mShouldClear) {
            mHandler.post(mCleanupRunnable);
        }
        mAnimationThread = null;

        if (mOnAnimationStop != null) {
            mOnAnimationStop.onAnimationStop();
        }
    }

    public OnFrameAvailable getOnFrameAvailable() {
        return mOnFrameAvailable;
    }

    public void setOnFrameAvailable(OnFrameAvailable frameProcessor) {
        this.mOnFrameAvailable = frameProcessor;
    }

    public interface OnFrameAvailable {
        Bitmap onFrameAvailable(Bitmap bitmap);
    }

    public OnAnimationStop getOnAnimationStop() {
        return mOnAnimationStop;
    }

    public void setOnAnimationStop(OnAnimationStop animationStop) {
        this.mOnAnimationStop = animationStop;
    }

    public void setOnAnimationStart(OnAnimationStart animationStart) {
        this.mOnAnimationStart = animationStart;
    }

    public interface OnAnimationStop {
        void onAnimationStop();
    }

    public interface OnAnimationStart {
        void onAnimationStart();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clear();
    }

    private void startAnimationThread() {
        if (canStart()) {
            mAnimationThread = new Thread(this);
            mAnimationThread.start();
        }
    }
}
