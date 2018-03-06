# CxxZookeeper

## C++ 版 zookeeper-3.4.11

### 目录
- [特点](#特点)
- [依赖](#依赖)
- [对比](#对比)
- [Support](#support)

#### 特点
* 兼容性：完全兼容java版zookeeper；
* 跨平台：同时支持Linux32/64、OSX64和WIN64三大平台，支持C++11及以上；

#### 对比
* 性能：未深入优化，目前release版本性能接近java版，使用zk-latencies.py测试得出的数据基本一致；
* 内存：c++版有明显优势，跟java版同环境下对比内存少用一个数量级，且无GC困扰；
* 更多的对比数据敬请期待~

#### 依赖
1. [CxxJDK](https://github.com/cxxjava/CxxJDK)
3. [CxxLog4j](https://github.com/cxxjava/CxxLog4j)

#### Support
Email: [cxxjava@163.com](mailto:cxxjava@163.com)



For more information about Apache ZooKeeper, please visit website at:

<http://zookeeper.apache.org/>

