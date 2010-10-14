package org.apache.zookeeper;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

public class ThreadUtil {

    public static ThreadGroup getRootThreadGroup() {
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentThreadGroup;
        while ( null != (parentThreadGroup = threadGroup.getParent()) ){
            threadGroup = parentThreadGroup;          
        }
        return threadGroup;
    }
    
    public static Thread[] getAllThreads() {
        final ThreadGroup root = getRootThreadGroup();
        int arraySize = ManagementFactory.getThreadMXBean().getThreadCount();
        int returnedThreads = 0;
        Thread[] threads;
        do {
            arraySize *= 2;
            threads = new Thread[arraySize];
            returnedThreads = root.enumerate( threads, true );
        } while ( returnedThreads >= arraySize );
        return java.util.Arrays.copyOf( threads, returnedThreads );
    }
    
    public static List<Thread> getThreadsFiltered(String pattern) {
        Thread[] allThreads = getAllThreads();
        ArrayList<Thread> filteredThreads = new ArrayList<Thread>();
        
 
        for(int i=0;i<allThreads.length;++i){
            Thread currentThread = allThreads[i];
            if(currentThread.getName().contains(pattern)){
                filteredThreads.add(currentThread);
            }
        }
        return filteredThreads;
    }
    
    public static List<Thread> getThreadsFiltered(String pattern, Thread exclude){
        List<Thread> filteredThreads = getThreadsFiltered(pattern);
        filteredThreads.remove(exclude);
        return filteredThreads;
    }
    
    public static String formatThread(Thread thread){
        StringBuilder out = new StringBuilder();
        out.append("Name: ")
           .append(thread.getName())
           .append(" State: ")
           .append(thread.getState())
           .append(" Prio: ")
           .append(thread.getPriority())
           .append("\nTrace:\n");
        
        StackTraceElement[] trace = thread.getStackTrace();
        
        for(int i=0;i<trace.length;++i){
            out.append(trace[i]).append("\n");
        }
           
        return out.toString();
    }
}
