package com.felipecsl.gifimageview.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.felipecsl.gifimageview.app.utils.ConvertUtils;
import com.felipecsl.gifimageview.library.GifImageView;

import java.io.InputStream;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MainActivity";
    private GifImageView mGifImageView;
    private Button mBtnToggle;
    private Button mBtnBlur;
    private boolean mShouldBlur = false;
    private Blur mBlur;

    @SuppressLint("StaticFieldLeak")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mGifImageView = findViewById(R.id.gifImageView);
        mBtnToggle = findViewById(R.id.btnToggle);
        mBtnBlur = findViewById(R.id.btnBlur);
        final Button btnClear = findViewById(R.id.btnClear);

        mBlur = Blur.newInstance(this);
        mGifImageView.setOnFrameAvailable(bitmap -> {
            if (mShouldBlur) {
                return mBlur.blur(bitmap);
            }
            return bitmap;
        });

        mGifImageView.setOnAnimationStop(() -> runOnUiThread(() ->
                Toast.makeText(MainActivity.this, "Animation stopped", Toast.LENGTH_SHORT).show()));

        mBtnToggle.setOnClickListener(this);
        btnClear.setOnClickListener(this);
        mBtnBlur.setOnClickListener(this);

        final InputStream is = getResources().openRawResource(R.raw.gif);
        mGifImageView.setBytes(ConvertUtils.inputStream2Bytes(is), true);
        mGifImageView.startAnimation();
        Log.d(TAG, "GIF width is " + mGifImageView.getGifWidth());
        Log.d(TAG, "GIF height is " + mGifImageView.getGifHeight());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.show_grid) {
            startActivity(new Intent(this, GridViewActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onClick(final View v) {
        if (v.equals(mBtnToggle)) {
            if (mGifImageView.isAnimating()) {
                mGifImageView.stopAnimation();
            } else {
                mGifImageView.startAnimation();
            }
        } else if (v.equals(mBtnBlur)) {
            mShouldBlur = !mShouldBlur;
        } else {
            mGifImageView.clear();
        }
    }
}
