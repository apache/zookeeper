/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is a generic Main class that is completely driven by the
 * /mainClasses resource on the class path. This resource has the
 * format:
 * <pre>
 * cmd:mainClass:Description
 * </pre>
 * Any lines starting with # will be skipped
 *
 */
public class FatJarMain {
    static class Cmd {
        Cmd(String cmd, String clazz, String desc) {
            this.cmd = cmd;
            this.clazz = clazz;
            this.desc = desc;
        }
        String cmd;
        String clazz;
        String desc;
    }
    static Map<String, Cmd> cmds = new HashMap<String, Cmd>();
    static List<String> order = new ArrayList<String>();
    
    /**
     * @param args the first parameter of args will be used as an
     * index into the /mainClasses resource. The rest will be passed
     * to the mainClass to run.
     * @throws IOException 
     * @throws ClassNotFoundException 
     * @throws NoSuchMethodException 
     * @throws SecurityException 
     * @throws IllegalAccessException 
     * @throws IllegalArgumentException 
     */
    public static void main(String[] args) throws IOException, ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException {
        InputStream is = FatJarMain.class.getResourceAsStream("/mainClasses");
        if (is == null) {
            System.err.println("Couldn't find /mainClasses in classpath.");
            System.exit(3);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while((line = br.readLine()) != null) {
            String parts[] = line.split(":", 3);
            if (parts.length != 3 || (parts[0].length() > 0 && parts[0].charAt(0) == '#')) {
                continue;
            }
            if (parts[0].length() > 0) {
                cmds.put(parts[0], new Cmd(parts[0], parts[1], parts[2]));
                // We use the order array to preserve the order of the commands
                // for help. The hashmap will not preserver order. (It may be overkill.)
                order.add(parts[0]);
            } else {
                // Just put the description in
                order.add(parts[2]);
            }
        }
        if (args.length == 0) {
            doHelp();
            return;
        }
        Cmd cmd = cmds.get(args[0]);
        if (cmd == null) {
            doHelp();
            return;
        }
        Class<?> clazz = Class.forName(cmd.clazz);
        Method main = clazz.getMethod("main", String[].class);
        String newArgs[] = new String[args.length-1];
        System.arraycopy(args, 1, newArgs, 0, newArgs.length);
        try {
            main.invoke(null, (Object)newArgs);
        } catch(InvocationTargetException e) {
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            } else {
                e.printStackTrace();
            }
        }
    }
    
    private static void doHelp() {
        System.err.println("USAGE: FatJarMain cmd args");
        System.err.println("Available cmds:");
        for(String c: order) {
            Cmd cmd = cmds.get(c);
            if (cmd != null) {
                System.err.println("  " + c + " " + cmd.desc);
            } else {
                System.err.println(c);
            }
        }
        System.exit(2);
    }

}
