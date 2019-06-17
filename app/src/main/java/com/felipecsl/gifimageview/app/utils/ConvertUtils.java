package com.felipecsl.gifimageview.app.utils;

import com.felipecsl.gifimageview.app.constant.MemoryConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author weixia
 * @date 2019/6/17.
 */
public final class ConvertUtils {

    public static byte[] inputStream2Bytes(final InputStream is) {
        if (is == null) {
            return null;
        }
        return input2OutputStream(is).toByteArray();
    }

    public static ByteArrayOutputStream input2OutputStream(final InputStream is) {
        if (is == null) {
            return null;
        }
        try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final byte[] b = new byte[MemoryConstants.KB];
            int len;
            while ((len = is.read(b, 0, MemoryConstants.KB)) != -1) {
                os.write(b, 0, len);
            }
            return os;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
