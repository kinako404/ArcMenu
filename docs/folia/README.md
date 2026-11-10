# ArcMenu on Folia（移植总览）

本 fork（`kinako404/ArcMenu`，分支 `folia`，版本号 `3.0.1-folia.N`）在上游 [FENTAIIII/ArcMenu](https://github.com/FENTAIIII/ArcMenu) 的基础上让 ArcMenu 能在 **Folia**（区域多线程服务端）上运行，同时**不破坏普通 Paper**：在 Paper 上，所有「跳到某个线程执行」都退化成在主线程当场执行，行为尽量与上游一致。

> 状态：开发中。线程模型的细节由 `folia-runtime`、`folia-reload-api` 两条分支完成后补全（见各分支的 `docs/folia/<分支名>.md`）。

## 支持的平台与版本

| 平台 | 版本 | 验证程度 |
|---|---|---|
| Paper | 1.21.1、1.21.4、1.21.6、1.21.11、26.1.2、26.2、26.3 | 编译 + 单元测试矩阵（`scripts/test-paper-version-matrix.sh`，CI 跑其中 1.21.1 / 1.21.11 / 26.2 / 26.3）；游戏内只在 Paper 26.3 build 246 上实测 |
| Folia | Shiroha 26.3（Folia 分叉，`26.3-576-e2e7192`） | 游戏内实测 |
| Folia 1.21.x / 26.1–26.2 | — | 未测。ArcMenu 用到的 Folia 调度 API（`EntityScheduler`、`GlobalRegionScheduler`、`AsyncScheduler`、`Bukkit.isOwnedByCurrentRegion`）在 paper-api 1.21.1 起就有，理论上可用 |

- **Java**：1.21.x 用 Java 21，26.x 用 Java 25。插件字节码目标是 Java 21（class major 65），在 Java 25 的服务端上照常加载。
- **编译**：不需要 `dev.folia:folia-api`（它只发布到 1.21.4，且没有 26.x）；paper-api 自带 Folia 的调度接口。缺省编译基线仍是上游的 Paper API 1.21.1 + ProtocolLib 5.4.0 + Java 21，CI 再对更新的版本编译。
- **`plugin.yml`**：`folia-supported: true`（没有它 Folia 拒绝加载：`not marked as supporting Folia`）；`api-version: '1.21'`——1.21.1 到 26.3 都接受这个值，而写成 `26.x` 会让 1.21 服务端拒绝加载，所以保持不变。

## ProtocolLib

ProtocolLib 是硬依赖。

| 服务端 | ProtocolLib |
|---|---|
| Paper 1.21.1–1.21.11 | 5.4.0 正式版 |
| Paper 26.x、Folia / Shiroha 26.3 | GitHub「Development Build」（release tag `dev-build`，`5.5.0-SNAPSHOT`）。26.3 需要 **9cb125f 或更新**（「Update to Minecraft 26.3 (#3681)」，11-04 构建），它的 `paper-plugin.yml` 带 `folia-supported: true` |

Maven 仓库 `repo.dmulloy2.net` 上的 `5.5.0-SNAPSHOT` 比 dev-build 旧，编译 26.x 时用 `scripts/install-protocollib-dev.sh` 把 dev-build 装进本地仓库（CI 也是这样做的）。

## JOML

JOML 由服务端提供（`provided`），**不能 shade / relocate**：Bukkit 的 `Transformation` 等 API 直接收发 `org.joml` 类型，换了包名就对不上。各版本服务端自带的 JOML：Minecraft 1.21.1 = 1.10.5，1.21.4–26.2 = 1.10.8，26.3 = 1.10.9。根 pom 把编译用的 JOML 钉在最低的 1.10.5，所以：

- 只能调用在 1.10.5–1.10.9 里签名都没变的方法。已知的坑：`Quaternionf.dot(Quaternionf)` 在 1.10.9 改成了 `dot(Quaternionfc)`，用 1.10.5 编译出的调用在 26.3 上抛 `NoSuchMethodError`（上游同样有这个问题）；ArcMenu 改成了手算点积。
- `scripts/check-joml-compat.py` 检查编译产物里引用的每个 `org.joml` 成员能否在 1.10.5 / 1.10.8 / 1.10.9 里解析，CI 每个矩阵项都跑。

## 线程模型（概要）

Folia 上没有「主线程」：每个区域一个 tick 线程，玩家实体归所在区域的线程所有，另有一个全局区域线程。`BukkitScheduler` 在 Folia 上直接抛 `UnsupportedOperationException`，所以 ArcMenu 全部改用 `platform/ArcScheduler`：

1. **玩家的所属线程**（玩家实体调度器）：一个玩家的菜单会话及其拥有的一切（展示 / 交互实体、指针摄像机、动画时间线、悬停、输入）只在该玩家的所属线程上读写；每个玩家一个 tick 定时器。以该玩家为执行者的动作、占位符、物品、声音、聊天也在这里。事件和命令入口若不在所属线程，先跳过去再执行。菜单实体必须生成在玩家当前所在的区域；玩家传送到别的区域后，菜单在新区域重建，旧实体交回旧区域的线程删除。
2. **全局区域线程**：目录快照（菜单 / 动画 / 模板 / 设置）的发布、命令表注册与注销、控制台命令动作、reload 与 CraftEngine 回调的编排；完成后再分发到各玩家线程（关闭菜单、刷新命令补全）。
3. **异步线程**：状态文件保存、资源包重建与 YAML 解析的 IO、workspace / market 网络请求。异步线程上不碰实体和世界。
4. **共享结构**：会话表用并发表、按玩家归属；配置与目录用 `@Volatile` 引用指向不可变快照；计数用 `LongAdder`。

在普通 Paper 上：`ArcScheduler` 的「是否在所属线程」都等于「是否在主线程」，实体 / 全局调度器都是 Paper 在主线程上的原生实现，所以跳线程都退化为当场执行。与上游可见的差异只有：每个玩家各一个 tick 定时器（替代一个全局定时器）、状态异步保存、应用 API 的 `runLater` / `runTimer` 返回的 `BukkitTask.taskId` 恒为 -1。

公开 API 的线程契约（草案，见 `folia-reload-api` 分支）：注册类方法可在任意线程调用；作用于某个玩家的方法要在该玩家的所属线程调用（Paper 上即主线程），否则抛 `IllegalStateException`；应用回调在玩家所属线程执行，同一玩家串行、**不同玩家在 Folia 上可能并发**，跨会话共享的状态要自己保证线程安全。

## 不支持热重载 / PlugMan

Folia 本身不支持 `/reload`。用 PlugMan 之类在运行中卸载 / 重新加载 ArcMenu **不受支持**：插件停用后不能再调度任务，正在别的区域里的菜单实体可能没法从正确的线程删除（这些实体不持久化，区块卸载时会消失）。要换 jar 请重启服务端。ArcMenu 自己的 `/arcmenu reload`（重新读配置和菜单）是支持的。

## 已知限制

- **mouse 模式点不中**：26.3 协议下鼠标模式的取样失效（Paper 26.3 与 Folia 都有，与 Folia 无关），见摸底报告 audit-input-render B1。
- **跨区域传送后的重锚**：开着菜单在同一世界内传送到另一个区域，旧版 WIP 会丢菜单，由 `folia-runtime` 分支修复。
- **未在 Folia 上验证**：CraftEngine 桥接的游戏内表现、PlaceholderAPI / Vault / PlayerPoints、Fabric 编辑器桥、market / download、死亡重生 / 换维度 / 区域合并分裂、多人同时开菜单。
- Folia 上 `/arcmenu reload`、编辑器保存、workspace 安装可能在不同区域线程上并发触发，由 `folia-reload-api` 分支串行化。

## 与上游的差异

- 调度：`ArcScheduler` 取代全部 `BukkitScheduler` / `isPrimaryThread` 用法；按玩家实体调度器 tick；会话表并发化；状态异步保存。
- `plugin.yml` 加 `folia-supported: true`。
- `StableDisplayTransforms.alignHemisphere` 不再调用 `Quaternionf.dot`（修复 26.3 上的 `NoSuchMethodError`，Paper 也受益）。
- 构建：CI 改成版本矩阵（1.21.1 / 1.21.11 用 JDK 21，26.2 / 26.3 用 JDK 25 + ProtocolLib dev-build），加 JOML 链接检查；新增 `scripts/install-protocollib-dev.sh`、`scripts/test-paper-version-matrix.sh`、`scripts/check-joml-compat.py`；PowerShell 矩阵补上 26.3。
- 版本号带 `-folia.N` 后缀；给上游提 PR 时去掉。

## 构建与测试

```bash
mvn clean verify                                       # 缺省：Paper API 1.21.1 + ProtocolLib 5.4.0，Java 21+
scripts/install-protocollib-dev.sh                     # 26.x 需要：把 ProtocolLib dev-build 装进 ~/.m2
mvn -Dpaper.version=26.3.build.246-stable -Dprotocollib.version=5.5.0-SNAPSHOT clean verify   # Java 25
python3 scripts/check-joml-compat.py paper/target/ArcMenu-*.jar
ARCMENU_JDK21=… ARCMENU_JDK25=… scripts/test-paper-version-matrix.sh -k   # 本地全矩阵
```
