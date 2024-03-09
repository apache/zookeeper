package org.apache.zookeeper.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * String convert method collection
 */
public class StringConvertUtil {
    public final static String EMPTY_STRING = "";

    public final static String COMMA = ",";

    public static List<String> parseList(String str, String splitStr) {
        List<String> list = new ArrayList<String>();
        if (StringConvertUtil.isBlank(str) || StringConvertUtil.isBlank(splitStr)) {
            return list;
        }
        return StringConvertUtil.toArrayList(str.split(splitStr));

    }

    public static Map<String, String> parseMap(String str, String splitStr) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (StringConvertUtil.isBlank(str) || StringConvertUtil.isBlank(splitStr))
            return map;
        List<String> list = StringConvertUtil.toArrayList(str.split(splitStr));
        for (String ip : list) {
            ip = trimToEmpty(ip);
            map.put(ip, ip);

        }
        return map;

    }

    public static boolean isBlank(String str) {
        if (null == str || trimToEmpty(str).isEmpty())
            return true;
        return false;
    }

    public static String trimToEmpty(String str) {

        if (null == str || str.isEmpty()) {
            return EMPTY_STRING;
        }
        return str.trim();
    }

    public static ArrayList<String> toArrayList(String[] array) {
        ArrayList<String> arrayList = new ArrayList<String>();
        if (null == array || 0 == array.length) {
            return arrayList;
        }
        for (int i = 0; i < array.length; i++) {
            arrayList.add(array[i]);
        }
        return arrayList;
    }

    public static void startThread(Runnable runnable) {
        if (null == runnable) {
            return;
        }
        try {
            Thread thread = new Thread(runnable);
            thread.start();

        } catch (Exception e) {
        }
    }
}
