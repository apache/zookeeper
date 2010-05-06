==========================================
ZooInspector - Browser and Editor for ZooKeeper Instances
Author: Colin Goodheart-Smithe
Date: February 2010
==========================================

ZooInspector is a Java Swing based application for browsing and editing ZooKeeper instances.

Contents
--------
	- Features
	- Pre-requisites
	- Build Instructions
	- Using ZooInspector
	- Creating and Using Plugins
	
Features
--------
	Below is a list of features in the current release of ZooInspector.
	- Load connection settings from a zookeeper properties file
	- Plugable DataEncryptionManagers to specify how data should be encrypted and decrypted in the Zookeeper instance
	- Browseable tree view of the ZooKeeper instance
	- View the data in a node
	- View the ACL's currently applied to a node
	- View the metadata for a node (Version, Number of Children, Last modified Tiem, etc.)
	- Plugable NodeViewers interface
	- Ability to save/load and set default Node Viewers
	
Pre-requisites
--------------
	- The main zookeeper build script must have been run before building this module
	
Build Instructions
------------------
	1. Open a command line.
	2. cd into this directory
	3. Run command: ant
	4. ZooInspector will be built to ../../../build/contrib/ZooInspector
	5. Copy zookeeper-3.x.x.jar into the lib sub-directory (if you are using zookeeper-3.3.1.jar it will have been
       copied to this directory during the build
	6. By default the zookeeper.cmd and zookeeper.sh files expect zookeeper-3.3.1.jar.  If you are using another version
	   you will need to change these files to point to the zookeeper-3.x.x.jar you copied to the lib directory
	7. To run ZooInspector run zooInspector.cmd (on Windows) or zooInspector.sh (on Linux).  If you are using 
	   zookeeper-3.3.1.jar and do not require any classpath changes you can run the zookeeper-dev-ZooInspector.jar
	   directly

Using ZooInspector
------------------
	To start ZooInspector run zooInspector.cmd (on Windows) or zooInspector.sh (on Linux).  If you are using 
	zookeeper-3.3.1.jar and do not require any classpath changes you can run the zookeeper-dev-ZooInspector.jar
	directly.
	
	Click the play button on the toolbar to bring up the connection dialog.  From here you can enter connection 
	information for your zookeeper instance.  You can also load the connection properties from a file.  This file can 
	have the format as a normal zookeeper properties file (i.e. hosts and timeout key-value pairs) and van optional have
	an encryptionManager key-value pair to specify the DataEncryptionManager to use for this connection 
	(DataEncryptionManagers are explained in further detail in the 'Creating and Using Plugins' section below).  You can
	also set the entered information as the defaults so that when you first start ZooInspector these settings are 
	automatically loaded into this dialog.  Pressing the OK button with connect to your ZooKeeper instance and show the
	current node tree on the left of the main panel.
	
	Clicking a node in the node tree will load the data for that node into the node viewers.  Three node viewers are 
	currently distributed with ZooInspector:
		1. Node Data - This enables you to see the data current stored on that node.  This data can be modified and 
		   saved.  The data is decrypted and encrypted using the DataEncryptionManager specified on the connection 
		   dialog.
		2. Node Metadata - This enables you to see the metadata associiated with this node.  This is Essentially the data
		   obtained from the Stat object for this node.
		3. Node ACLs - This allows you to see the ACLs currently applied to this node.  Currently there is no ability
		   to change the ACLs on a node, but it is a feature I would like to add.
	Other custom Node Viewers can be added, this is explained in the 'Creating and Using Plugins' section below.
	

Creating and Using Plugins
--------------------------
	There are two types of plugin which can be used with ZooInspector:
		1. DataEncryptionManager - This specifies how data should be encrypted and decrypted when working with a 
		   zookeeper instance.
		2. ZooInspectorNodeViewer - This is a GUI panel which provides a view of visualisation on a node.
	More information on these interfaces can be found in the javadocs for this module.
	
	To use a plugin in ZooInspector, build the plugin to a jar and copy the jar to the lib sub-directory.  Edit the 
	zooInspector.cmd and/or zooInspector.sh files to include your new jar on the classpath and run ZooInspector.
	
	For DataEncryptionManagers, click the play button to open the connection dialog and enter the full class name of 
	your DataEncryptionManager in the 'Data Encryption Manager' field.  You can make this Data Encryption Manager the 
	default by clicking 'Set As Default'.  Click the 'OK' button to instantiate and use your plugin.
	
	For ZooInspectorNodeViewers, Click the 'Change Node Viewers' button on the toolbar (looks like a tree with a pencil)
	and enter the full classname for your Node Viewer in the field left of the 'Add' button, then click the 'Add' 
	button.  The Node Viewer will be instantiated and should appear in the list.  You can change the order of the Node 
	viewers by clicking the up and dpwn buttons and delete a Node Viewer by clicking the delete button.  You can save 
	to configuration to a file or set it as the default if necessary. Then click the 'OK' button and your Node Viewer 
	should appear in the tabs on the right of the main panel.
