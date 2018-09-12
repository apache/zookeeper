package org.apache.zookeeper;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class BenchMain {
    public static void main(String args[]) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
