package org.apache.zookeeper.util;


public class Tester {

    public static void main(String[] args){

        TransportTreeFactory factory = new SimpleTransportTreeFactory();

        byte[] zopfData = "Zorbaffloz".getBytes();
        TransportTree zopf = factory.makeNewTree("zopf", zopfData, null);

        TransportTree zepf = factory.makeNewTree("zepf", zopfData, null);

        zopf.addChild(zepf);

        for(TransportTree child : zopf){
            System.out.println(child.getName());
        }

    }
}
