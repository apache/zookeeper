Apache ZooKeeper .NET async Client
-
![ZooKeeper logo](https://raw.githubusercontent.com/shayhatsor/zookeeper/trunk/zookeeper.bmp)
[![NuGet](https://img.shields.io/github/release/shayhatsor/zookeeper.svg?style=flat&label=Latest%20Release)](https://github.com/shayhatsor/zookeeper/releases/latest)
* Supports .NET 4.5 and above, including .NET Core.
* Fully Task-based Asynchronous (async/await).
* Follows the logic of the official Java client to the letter, in fact the code is almost identical. 
* NuGets
  * Client [ZooKeeperNetEx](https://www.nuget.org/packages/ZooKeeperNetEx)
  * Recipes [ZooKeeperNetEx.Recipes](https://www.nuget.org/packages/ZooKeeperNetEx.Recipes)


#### Build From Source
##### Prerequisites
1. [Oracle JDK (Java Developer Kit)](http://www.oracle.com/technetwork/java/javase/downloads/index.html).
1. [Apache Ant](http://ant.apache.org/manual/install.html).
2. [Visual Studio 2017 with .NET Core](https://www.microsoft.com/net/core#windowsvs2017).

##### Build Steps
1. Run `ant` on the repository's root folder.
3. Open `src\csharp\ZooKeeperNetEx.sln` with Visual Studio 2017.
4. Build.
