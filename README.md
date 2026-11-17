# Minecraft Memory Patches

五个可独立安装的 Fabric 服务端补丁，针对玩家对象引用滞留、弹射物日志上下文、地图状态分配抖动及数据库故障期间的队列积压。适配 Minecraft **26.3 / Java 25 / Fabric Loader 0.19.5+**。

[问题、转储观测、代码分析与修复方案](docs/ANALYSIS.zh-CN.md) · [测试与限制](docs/VALIDATION.zh-CN.md) · [发布隐私白名单](docs/PRIVACY.zh-CN.md)

| 补丁 | 版本 | 目标 |
| --- | --- | --- |
| [地图玩家清理](dist/patch-map-player-leak-1.0.0.jar) | 1.0.0 | 清理地图对已移除玩家的引用 |
| [假人网络注销](dist/patch-fakeplayer-network-leak-1.0.1.jar) | 1.0.1 | 补齐 Fabric 网络注销并保障异常路径清理 |
| [TIS 弹射物上下文](dist/patch-tis-projectile-threadlocal-1.0.1.jar) | 1.0.1 | 阻止工作线程的构造上下文强持有实体 |
| [ConfluxMap 分配优化](dist/patch-confluxmap-chunkstate-churn-1.0.1.jar) | 1.0.1 | 减少区块状态轮询的反复分配，释放空维度桶 |
| [Ledger 队列与小库恢复](dist/patch-ledger-queue-guard-1.0.2.jar) | 1.0.2 | 并发容量保护，启动时有条件重建异常小库 |

下载需要的 JAR，放入服务端 `mods/`，替换同模块旧版后重启。不要并装同模块的新旧版本。SHA-256 校验值见 [SHA256SUMS](SHA256SUMS)。

Ledger 默认队列容量为 32768；超限时默认丢弃新动作。`-Dledger.queueGuard.dropOldest=true` 改为丢弃旧动作；`-Dledger.queueGuard.maxSize=整数` 调整容量，非正数关闭队列保护。丢弃记录会影响审计和回滚完整性。

Ledger 仅在**启动打开默认 SQLite 连接前**，对确认损坏、缺表或缺列，且主文件和 WAL/rollback journal 合计严格小于 **10,000 字节**的库自动备份并重建。健康小库、达到阈值或被占用的库不会重置。运行中不热删除数据库；运行期间出现的问题在下次启动检查。

## 目标依赖

| 模块 | 已验证目标版本 |
| --- | --- |
| 地图 | Lithium 0.26.2+mc26.3 的 framed_maps 路径；补丁本身未强制依赖 Lithium |
| 假人网络 | Fabric Networking API v1 6.3.8+fcdff87f5d，Carpet 假人路径 |
| TIS | Carpet 26.3+v260915，Carpet TIS Addition 1.82.4 |
| ConfluxMap | 0.1.12 |
| Ledger | 1.3.25 |

补丁使用内部接口与私有字段；目标模组升级后需重新适配，不承诺其他版本兼容。

## 构建与测试

需要合法获取的本地 Fabric 服务端与目标模组。本仓库不分发 Minecraft、生产模组、数据库、转储或原始日志。

```bash
export MC_DIR=/path/to/local/fabric-server
export MODS_ZIP=/path/to/local/mods-archive.zip
bash build-all.sh
bash tests/database-recovery/verify.sh
RECOVERY_FIXTURE=corrupt bash tests/verify-harness/verify-all.sh
RECOVERY_FIXTURE=missing DROP_OLDEST=true bash tests/verify-harness/verify-all.sh
python3 scripts/check-publication.py
```

`MODS_ZIP` 必须含构建脚本列出的精确 `mods/*.jar` 文件名。需要 Bash、Python 3、GNU coreutils 及 JDK 25。测试会创建临时隔离世界、接受测试服 EULA，并加载本地提供的模组；运行前应自行确认对这些软件的使用授权。

公开文档中的数量是脱敏聚合值，部分内存大小来自原解析器的估算。已验证修复的基本行为与故障路径，尚未完成长期生产压力测试；不能把估算当作内存节省保证。
