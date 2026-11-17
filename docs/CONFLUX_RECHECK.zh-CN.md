# ConfluxMap 原版分配、队列滞留与统计口径更正

## 结论

1. **原版存在真实、持续、受检查预算限制的分配浪费，但前文“极端 GC churn”的措辞过重。** 在本机受控场景，稳定检查 2048 项/tick 约分配 144 KiB/tick，按 20 TPS 折算约 2.81 MiB/s；对应组件耗时约 0.18 ms/tick。这不足以单独证明生产卡顿，也不能解释数 GB 长期存活堆。
2. **本地补丁明显缓解该路径**：稳定、无订阅者场景测得 0 B/tick，约 0.013 ms/tick。该结果只针对被测组件及路径，不等于整个 ConfluxMap 零分配。
3. **较新原版未修复这条算法**：现场 0.1.12、稳定版 0.1.16、预发布 0.1.17-rc.1 的 ChunkLoadStateService、ChunkLoadStatePublisher 与 LiveDirtyQueue 三个 class 均逐字节相同。0.1.17-rc.1 实测仍分配 144 KiB/tick。当前主分支源码也保留同样结构。
4. **发现另外两类问题**：多订阅者轮转和冗余发布仍会制造小量分配；另有两个按需求清理的队列，在无地图需求时会保留已卸载区块的键，后者更值得继续修复。现有 ConfluxMap 补丁未覆盖 LiveChunkSummaryTracker / LiveDirtyQueue。

## 垃圾占比不等于 GC 耗时占比

可核对的原始分析记录中，剔除疑似填充数组后的死对象估算约411.72 MiB，其中相关LinkedHashMap.Entry约147.49 MiB，两者之比约35.8%。这只是当次快照、该筛选条件和估算口径下的死对象字节占比。

“ConfluxMap占GC的44%”没有可复核的原始分母或GC时间测量支持，不作为本文结论。若将垃圾数量或字节比例称为GC耗时比例，应当纠正。不能在未确认统计口径时把35.8%、对象数量占比、分配速率占比和GC耗时占比混用。

堆转储记录某时刻尚未回收的对象；已回收对象不再出现，不同区域的回收时机也不同。相对比例较高可以与每秒几MiB的绝对分配量同时成立，不能由此推导整个运行期间的分配份额。G1的扫描、记忆集处理和存活对象复制等耗时，也不按各来源死对象字节数同比分摊。[Oracle G1说明](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html)

反过来，本次约0.18 ms/tick测的是组件调用耗时，也不是GC线程耗时；不能拿它证明生产GC影响很小。要量化GC改善，仍需同负载下的分配采样、GC阶段日志、暂停统计和补丁前后对照。

## 实验对象与方法

对象：归档原版 0.1.12；0.1.12 + patch-confluxmap-chunkstate-churn 1.0.1；官方发布的 0.1.17-rc.1。额外下载 0.1.16 做核心 class 哈希对照，没有重复跑其完整 CPU 基准。稳定版与预发布来源为[官方 Releases](https://github.com/Conflux-Union/conflux-map/releases)，检查时最新稳定为 0.1.16，最新预发布为 0.1.17-rc.1。

环境：Java 25.0.4 / Oracle GraalVM、Fabric 0.19.5、Minecraft 26.3、本地归档模组集；各变体使用独立临时世界、同一套 JVM 参数：G1、8 MiB Region、Xms512M/Xmx2G。测试在服务端主线程直接调用真实服务类，使用 ThreadMXBean 的当前线程分配与 CPU 时间计数。每个场景预热 8000 次，测量三轮，每轮 12000 次；表中取三轮中位数。

包含实际加载的 25 区块场景。较大的 2048/11310 项场景用合成 LevelChunk 坐标、真实发布器和真实原版/补丁服务，并通过所有变体共有的测试 Mixin，将当前测试线程的 holder 查询替换为固定有效 holder 或 null。它避免数千真实区块的生成/I/O干扰，验证轮询和发布成本；**不代表加载了 11310 个真实区块，也不能直接作为生产 MSPT**。夹具额外成本两边都有，具体 JIT 优化仍可能不同。

本实验无玩家连接，订阅者为合成 UUID 和只计数的 sender，未测网络传输成本。运行加速循环而非每秒 20 次；“MiB/s@20 TPS”是由每 tick 字节数折算，不是整个测试进程的瞬时分配率。

## 主测量结果

11310 项索引，每 tick 实际 holder 检查均为 2048：

| 变体 | 稳态、0订阅：B/tick | 按20TPS折算 MiB/s | 组件耗时中位数 ms/tick |
| --- | ---: | ---: | ---: |
| 原版0.1.12 | 147456 | 2.8125 | 0.1767 |
| 本地补丁1.0.1 | 0 | 0 | 0.0131 |
| 原版0.1.17-rc.1 | 147456 | 2.8125 | 0.1990 |

原版 2048 项与 11310 项的分配相同，是因为达到每 tick 2048 项预算后不再线性增长。CPU 随索引、树查找与 JIT 状态变化。较新原版的时序差异不能归因于回归：核心 class 相同，各进程的编译、后台线程和调度不同，未做统计显著性检验。

原版没有 holder 的11310项场景为 81920 B/tick，约1.56 MiB/s；补丁为24 B/tick。只有25个真实区块时，原版三轮约2.4–4.2 KB/tick，补丁约24–56 B/tick。小场景的 JIT 收敛更明显，不拿其中某一轮作固定常数。

## “拆装”到底有多严重

原服务每次轮转删除再添加 LinkedHashSet 节点，确有节点重新分配；而 ArrayDeque 首尾轮转微测试没有相同的节点分配。单独轮转微测试约71–72 B/次（该测法还可能保留迭代器）；不能把它与服务整体的每项72 B简单相加。

直接 Publisher.update 同一状态的微测试仍有32–56 B/次，Publisher.remove 不存在项约24 B/次；空闲 poll 为0。说明“没有发出新网络消息”不代表“没有构造对象”。同一 class 在不同调用形态下也会因逃逸分析呈现不同分配量。

因此，之前依据源码把 Optional、lambda、迭代器、临时键全部视作一定发生的堆分配不够准确：JIT 可以消除其中一部分，ThreadMXBean 的实际计数比静态 new 次数更有参考价值。

在本机受控实验，原版0.18 ms相当于50 ms tick预算约0.36%；从成本收益看值得优化，尤其叠加许多模组时。但没有生产 GC 暂停和全栈 CPU 证据，不能称其为主要卡顿源。两份堆中累计出现大量死节点，也不表示同量内存永久泄露。此实验未独立复算转储，也未测生产 GC 暂停改善。

## 是否已有缓解

- **本地补丁：是，主轮询分配已明显减少**，且仍检查2048项，不是通过跳过检查取得结果。
- **上游版本升级：这条路径没有算法性修复**，三个关键class在0.1.12/0.1.16/0.1.17-rc.1完全相同，哈希见 `class-hashes.json`。当前 master 同样保留 remove/add 和无变化 update。[ChunkLoadStateService](https://github.com/Conflux-Union/conflux-map/blob/014dbb55a3e153d79dccf9e990845baddbe60d09/src/main/java/cn/net/rms/confluxmap/server/ChunkLoadStateService.java)
- **配置：有绕开路径**。0.1.12和较新版默认 `shareChunkLoadState=false`，且启动时只有 `enabled && shareChunkLoadState` 才创建该服务；关闭后重启可避免该模块成本。这是原本已有的开关，并非新版本修复。开启后，即使没有订阅者仍会轮询。[Companion调用条件](https://github.com/Conflux-Union/conflux-map/blob/014dbb55a3e153d79dccf9e990845baddbe60d09/src/main/java/cn/net/rms/confluxmap/server/ConfluxMapCompanion.java)
- 未检查到可据以确认生产部署/开关状态的运行实例，因此不能据实验宣称生产已经启用了补丁或已经改善。补丁当前依赖声明只支持0.1.12；不要因核心class相同就直接忽略版本约束安装到新版。

## 其他类似热点与新问题

### 1. 多订阅者仍有轮转分配：小量、非主要热点

稳定11310项、8订阅者时，补丁仍约600 B/tick；32订阅者约1280 B/tick，分别约11.7和25 KiB/s@20TPS。来源含 subscribers 的 remove/put、迭代器和不同 JIT 路径中的注入对象。不同编译状态会改变具体数值，不保证40字节×订阅者就是全部开销。

这比原版同场景约148 KB/tick小很多，属于后续可优化项，不应与主热点等量齐观。可考虑 access-order 链表直接移动节点或稳定游标结构，但必须保持订阅增删和公平性。

### 2. Publisher 构造后才判断“状态没变”，以及首次快照

Publisher.update 总先构造候选值并替换 TreeMap，再用equals决定是否向订阅者发布。现有补丁在调用前缓存ticket，稳态避免了它；Publisher本身没有改动。其他直接调用路径仍有同类浪费。

11310项完整快照重启微测试约0.25–0.31 MiB/次，约0.61–0.67 ms/次，三个变体受编译状态影响。该测法一次同步排空整份快照，真实服务会分tick发送，不能把完整耗时归到单tick。它是有请求触发的开销，不能称作每tick常驻分配。[Publisher源码](https://github.com/Conflux-Union/conflux-map/blob/014dbb55a3e153d79dccf9e990845baddbe60d09/common/src/main/java/cn/net/rms/confluxmap/core/net/ChunkLoadStatePublisher.java)

### 3. 无地图需求时，两个队列保留已卸载键：条件性滞留，未被当前补丁修复

LiveDirtyQueue.remove 只从逻辑set删除，ArrayDeque内旧引用仍在；mark在逻辑set已删除后可以重新追加同一个位置。仅容器测试中，mark/remove反复10万次后，逻辑set为0，但deque为10万；poll预算2048后还剩97952。

进一步使用真实 LiveChunkSummaryTracker 的 onChunkLoad/onChunkDirty/onChunkUnload/refreshLoadedChunks 调用链做夹具复现；仅屏蔽摘要捕获，避免摘要/磁盘I/O干扰，队列逻辑保持原版。在原版0.1.12与0.1.17-rc.1结果一致：

| 场景 | loadedChunks | refreshDeque / refreshSet | dirtyDeque / dirtySet |
| --- | ---: | ---: | ---: |
| 2000个不同位置加载→变脏→卸载 | 0 | 2000 / 2000 | 2000 / 0 |
| 之后无需求执行1000次刷新 | 0 | 2000 / 2000 | 2000 / 0 |
| 添加需求并执行100次刷新 | 0 | 0 / 0 | 0 / 0 |
| 同一位置循环2000次，仍无需求 | 0 | 1 / 1 | 2000 / 0 |

原因是 refreshLoadedChunks 在 activeDemands 与 watchedDemands 都空时提前返回，连陈旧项清理也不执行；onChunkUnload不立即清理refreshQueue/queuedForRefresh，dirtyQueue也只是逻辑删除。

这里滞留的是 LoadedKey 等队列记录，不是证实整批 LevelChunk 仍被保留。存在需求时或服务关闭时可释放，所以更准确的称呼是“按历史操作增长、清理由需求驱动的延迟滞留”。本实验没有证明生产已经累计了多少字节。它比有界分配更值得优先排查，因为长期无查看需求的服务器也会不断经历区块加载/卸载。

建议为无需求路径增加预算化的陈旧项清理，并解决dirty队列移除后重加的重复标记问题；避免简单在每次卸载用ArrayDeque线性remove引入新的大队列O(n)成本。修复应独立验证幂等、重载、公平性与空闲清理。此轮任务只做复测，没有修改现有发布补丁。[LiveDirtyQueue](https://github.com/Conflux-Union/conflux-map/blob/014dbb55a3e153d79dccf9e990845baddbe60d09/src/main/java/cn/net/rms/confluxmap/server/LiveDirtyQueue.java)、[调用链](https://github.com/Conflux-Union/conflux-map/blob/014dbb55a3e153d79dccf9e990845baddbe60d09/src/main/java/cn/net/rms/confluxmap/server/LiveChunkSummaryTracker.java)

## 可公开核对的聚合证据

- [三种变体的逐轮测量](benchmarks/conflux-allocation.json)：只含合成场景名、数量、分配字节与耗时。
- [队列生命周期复现](benchmarks/conflux-retention.json)：只含合成场景和容器数量。
- [关键类字节码SHA-256](benchmarks/conflux-class-hashes.json)：用于核对版本间相关class是否相同，不包含第三方二进制。

原始日志、完整上游JAR、现场转储和包含本机路径的实验运行目录不公开。本文为追加的补充更正；原文和旧提交保留，五个已发布补丁JAR未在本次更新中改动。
