package com.felipecsl.gifimageview.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import com.felipecsl.gifimageview.library.GifImageView;

import java.util.List;

public class GifGridAdapter extends BaseAdapter {
    private final Context mContext;
    private final List<String> mImageUrls;

    public GifGridAdapter(Context context, List<String> imageUrls) {
        this.mContext = context;
        this.mImageUrls = imageUrls;
    }

    @Override
    public int getCount() {
        return mImageUrls.size();
    }

    @Override
    public Object getItem(int position) {
        return mImageUrls.get(position);
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @SuppressLint("StaticFieldLeak")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final GifImageView imageView;
        if (convertView == null) {
            imageView = new GifImageView(mContext);
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            imageView.setPadding(10, 10, 10, 10);
            final int size = AbsListView.LayoutParams.WRAP_CONTENT;
            final AbsListView.LayoutParams layoutParams = new GridView.LayoutParams(size, size);
            imageView.setLayoutParams(layoutParams);
        } else {
            imageView = (GifImageView) convertView;
            imageView.clear();
        }
        new GifDataDownloader() {
            @Override
            protected void onPostExecute(final byte[] bytes) {
                imageView.setBytes(bytes);
                imageView.startAnimation();
            }
        }.execute(mImageUrls.get(position));
        return imageView;
    }
}
