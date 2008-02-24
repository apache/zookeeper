package com.yahoo.zookeeper.server;

public class ServerConfig {
	private int clientPort;
	private String dataDir;
	private String dataLogDir;
	
	protected ServerConfig(int port, String dataDir,String dataLogDir) {
		this.clientPort = port;
		this.dataDir = dataDir;
		this.dataLogDir=dataLogDir;
	}
	protected boolean isStandaloneServer(){
		return true;
	}

	public static int getClientPort(){
		assert instance!=null;
		return instance.clientPort;
	}
	public static String getDataDir(){
		assert instance!=null;
		return instance.dataDir;
	}
	public static String getDataLogDir(){
		assert instance!=null;
		return instance.dataLogDir;
	}
	public static boolean isStandalone(){
		assert instance!=null;
		return instance.isStandaloneServer();
	}
	
	protected static ServerConfig instance=null;
	
	public static void parse(String[] args) {
		if(instance!=null)
			return;
        if (args.length != 2) {
            System.err.println("USAGE: ZooKeeperServer port datadir\n");
            System.exit(2);
        }
        try {
          	instance=new ServerConfig(Integer.parseInt(args[0]),args[1],args[1]);
        } catch (NumberFormatException e) {
            System.err.println(args[0] + " is not a valid port number");
            System.exit(2);
        }
	}

}
