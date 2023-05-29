


public class Tester {

    public static void main(String[] args){

        TransportTreeFactory factory = new SimpleTransportTreeFactory();

        byte[] zopfData = "Zorbaffloz".getBytes();
        TransportTree zopf = factory.makeNewTree("zopf", zopfData);

        TransportTree zepf = factory.makeNewTree("zepf", zopfData);

        zopf.addChild(zepf);

        for(TransportTree child : zopf){
            System.out.println(child.getName());
        }

    }
}
