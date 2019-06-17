package com.felipecsl.gifimageview.app;

import android.util.Log;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings("WeakerAccess")
public class ByteArrayHttpClient {
    private static final String TAG = "ByteArrayHttpClient";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static byte[] get(final String urlString) {
        InputStream in = null;
        try {
            final String decodedUrl = URLDecoder.decode(urlString, "UTF-8");
            final URL url = new URL(decodedUrl);
            final Request request = new Request.Builder().url(url).build();
            final Response response = CLIENT.newCall(request).execute();
            final ResponseBody responseBody = response.body();
            if (responseBody != null) {
                in = responseBody.byteStream();
                return IOUtils.toByteArray(in);
            }
        } catch (final MalformedURLException e) {
            Log.d(TAG, "Malformed URL", e);
        } catch (final OutOfMemoryError e) {
            Log.d(TAG, "Out of memory", e);
        } catch (final UnsupportedEncodingException e) {
            Log.d(TAG, "Unsupported encoding", e);
        } catch (final IOException e) {
            Log.d(TAG, "IO exception", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (final IOException ignored) {
                }
            }
        }
        return null;
    }
}
