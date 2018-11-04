package org.apache.zookeeper.server.util;


public class TransformUtil {

    public static byte[] longToByteArray(long num) {
        byte[] result = new byte[8];
        result[0] = (byte) (num >>> 56);
        result[1] = (byte) (num >>> 48);
        result[2] = (byte) (num >>> 40);
        result[3] = (byte) (num >>> 32);
        result[4] = (byte) (num >>> 24);
        result[5] = (byte) (num >>> 16);
        result[6] = (byte) (num >>> 8);
        result[7] = (byte) (num);
        return result;
    }

    public static long byteArrayToLong(byte[] byteArray) {
        byte[] a = new byte[8];
        int i = a.length - 1, j = byteArray.length - 1;
        for (; i >= 0; i--, j--) {
            if (j >= 0)
                a[i] = byteArray[j];
            else
                a[i] = 0;
        }

        long v0 = (long) (a[0] & 0xff) << 56;
        long v1 = (long) (a[1] & 0xff) << 48;
        long v2 = (long) (a[2] & 0xff) << 40;
        long v3 = (long) (a[3] & 0xff) << 32;
        long v4 = (long) (a[4] & 0xff) << 24;
        long v5 = (long) (a[5] & 0xff) << 16;
        long v6 = (long) (a[6] & 0xff) << 8;
        long v7 = (long) (a[7] & 0xff);
        return v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7;
    }
}
