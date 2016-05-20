Apache ZooKeeper .NET async Client
-
![ZooKeeper logo](https://ccdce73789835b39c0952535276de3b7772f802f.googledrive.com/host/0B_hNtILvKRsifnpVRnp4NVRKcHltY05FSGM2bzg5ZGNaVHRqcW9GVFJnWjczSFUtNk40OTA/zookeeper.bmp)
[![NuGet](https://img.shields.io/github/release/shayhatsor/zookeeper.svg?style=flat&label=Latest%20Release)](https://github.com/shayhatsor/zookeeper/releases/latest)
* Supports .NET 4, 4.5 and above
* Fully Task-based Asynchronous (async/await).
* Follows the logic of the official Java client to the letter, in fact the code is almost identical. 
* NuGets
  * Client [ZooKeeperNetEx](https://www.nuget.org/packages/ZooKeeperNetEx)
  * Recipes [ZooKeeperNetEx.Recipes](https://www.nuget.org/packages/ZooKeeperNetEx.Recipes)

-
####Build From Source
#####Prerequisites
1. [Apache Ant](http://ant.apache.org/manual/install.html).
2. [Visual Studio 2015](https://www.visualstudio.com/en-us/downloads/download-visual-studio-vs.aspx) with [Update 2](http://go.microsoft.com/fwlink/?LinkId=691129).
3. [Microsoft .NET Core 1.0.0 RC2 - VS 2015 Tooling Preview 1](https://go.microsoft.com/fwlink/?LinkId=798481).

#####Build Steps
1. Run `ant` on the repository's root folder.
3. Run Visual Studio and open `ZooKeeperNetEx.sln` from `src\csharp`.
4. Build.
