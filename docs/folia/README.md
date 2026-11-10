# ArcMenu on Folia（移植总览）

本 fork（`kinako404/ArcMenu`，分支 `folia`，版本号 `3.0.1-folia.N`）在上游 [FENTAIIII/ArcMenu](https://github.com/FENTAIIII/ArcMenu) 的基础上让 ArcMenu 能在 **Folia**（区域多线程服务端）上运行，同时**不破坏普通 Paper**：在 Paper 上，所有「跳到某个线程执行」都退化成在主线程当场执行，行为尽量与上游一致（差异见「Paper 上与上游的可见差异」）。

`folia` 分支 = 上游 `e701cd0` + 10-26 WIP 的源码改动，再依次合并三条移植分支；每条分支的逐条改动、理由与当时的实测见同目录的分支文档：

| 文档 | 范围 |
|---|---|
| [folia-build.md](folia-build.md) | JOML 26.3 兼容、版本矩阵（CI / 脚本）、ProtocolLib dev-build 安装、本文档与 README |
| [folia-runtime.md](folia-runtime.md) | 核心运行时：跨区域重锚、入口跳线程、跨玩家动作、26.3 `AcceptTeleportation`、P2 并发修补、`ArcScheduler` 新增辅助 |
| [folia-reload-api.md](folia-reload-api.md) | reload 编排、单线程文件通道、编辑器 / workspace、公开 API 的线程契约与 `REGION_THREADING` |
| [touch-ux.md](touch-ux.md) | 分支 `folia-touch-ux`（未合并）：touch 模式界面跟随眼高、指针用客户端上报的转角（绕开 CraftBukkit `justTeleported` 丢包）、按客户端 tick 去重点击、新回调 `onSurfaceMoved` |

分支文档是各分支完成时的记录。其中留给集成的几项已在合并时处理：缺省基线对 1.21.1 编译（`ArcScheduler` 改反射）、README 的 API 章节（已补线程契约）、自定义菜单命令入口两边各做了一次跳线程（只保留 `MenuOpenCommand.execute` 里那一处）。

## 支持的平台与版本

| 平台 | 版本 | 验证程度（11-10，`folia` 合并后） |
|---|---|---|
| Paper | 1.21.1、1.21.4、1.21.6、1.21.11、26.1.2、26.2、26.3 | 编译 + 单元测试矩阵 7 项全过（每项 258 个测试，1 个上游原有的跳过）+ JOML 链接检查全过；CI 跑其中 1.21.1 / 1.21.11 / 26.2 / 26.3 |
| Paper 26.3 build 246 | 26.3 | 游戏内全量回归 18 个带断言的场景全过 |
| Folia（Shiroha 26.3，`26.3-576-e2e7192`） | 26.3 | 游戏内全量回归 18 个带断言的场景全过 |
| Paper 1.21.x / 26.1–26.2 服务端 | — | 没有游戏内实测，只有编译与单元测试 |
| Folia 1.21.x / 26.1–26.2 | — | 未测。ArcMenu 用到的 Folia 调度 API（`EntityScheduler`、`GlobalRegionScheduler`、`AsyncScheduler`、`Bukkit.isOwnedByCurrentRegion`）在 paper-api 1.21.1 起就有，理论上可用 |

- **Java**：1.21.x 用 Java 21，26.x 用 Java 25。插件字节码目标是 Java 21（class major 65），在 Java 25 的服务端上照常加载。
- **编译**：不需要 `dev.folia:folia-api`（它只发布到 1.21.4，且没有 26.x）；paper-api 自带 Folia 的调度接口。缺省编译基线仍是上游的 Paper API 1.21.1 + ProtocolLib 5.4.0 + Java 21；1.21.4 起才有的 `Bukkit.isGlobalTickThread()` 通过反射调用，所以基线能编译。CI 再对更新的版本编译。
- **发布用哪个 jar**：缺省基线（Paper API 1.21.1）编出的 jar 就能在 26.3 上运行（见上表），不需要按服务端版本分别构建。
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

- 只能调用在 1.10.5–1.10.9 里签名都没变的方法。已知的坑：`Quaternionf.dot(Quaternionf)` 在 1.10.9 改成了 `dot(Quaternionfc)`，用 1.10.5 编译出的调用在 26.3 上抛 `NoSuchMethodError`（上游同样有这个问题）；ArcMenu 改成了手算点积（`StableDisplayTransform.kt`，`JomlLinkageTest` 守住）。
- `scripts/check-joml-compat.py` 检查编译产物里引用的每个 `org.joml` 成员能否在 1.10.5 / 1.10.8 / 1.10.9 里解析，CI 每个矩阵项都跑。

## 线程模型

Folia 上没有「主线程」：每个区域一个 tick 线程，实体归所在区域的线程所有，另有一个全局区域线程。`BukkitScheduler` 在 Folia 上直接抛 `UnsupportedOperationException`，所以 ArcMenu 全部改用 `platform/ArcScheduler`。ArcMenu 的工作分在四类线程上：

| 线程 | 做什么 |
|---|---|
| 玩家的所属线程（玩家实体调度器） | 该玩家的菜单 / 应用会话及其拥有的一切：展示 / 交互实体、指针摄像机、动画时间线、悬停、输入；以该玩家为执行者的动作、占位符、物品、声音、聊天；应用回调 |
| 全局区域线程 | 发布目录快照（菜单 / 动画 / 模板 / 设置）、改 `commandMap`、`closeMenus` 分发、控制台命令动作、Vault 余额改动、CraftEngine 回调入口 |
| 文件通道 `ArcMenu-files`（单线程） | 一切读写 `plugins/ArcMenu/` 的动作：reload / validate / resources 的解析校验、资源包重建、模板重载、编辑器保存 / 应用 / 模板增删、workspace 的全部文件动作、市场包安装 |
| 其它异步线程 | 状态文件保存、workspace / 市场的网络请求（下载完再把安装交给文件通道） |

Paper 上：「是否在所属线程」都等于「是否在主线程」，实体 / 全局调度器都是 Paper 在主线程上的原生实现，所以跳线程都退化为当场执行；文件通道在 Paper 上同样存在（IO 不再占主线程）。

### 调度层 `ArcScheduler`

- `owns(entity)` / `owns(location)`：Folia 上 `Bukkit.isOwnedByCurrentRegion(...)`，Paper 上 `isPrimaryThread()`。`ownsGlobal()` 反射调用 `Bukkit.isGlobalTickThread`（找不到再找 `RegionizedServer.isGlobalTickThread`，都没有退回 `isPrimaryThread()`）。
- `execute(entity, task)`：已在所属线程就**当场执行**，否则排到实体调度器下一 tick；`later` / `timer` 走实体调度器；`global` / `globalLater` 走全局区域调度器（已在全局线程就当场执行）；`asyncLater` 走异步调度器。
- `remove(entity)`：在实体的所属线程删除；实体不在本区域且插件已停用（热卸载）时放弃，非持久实体留给区块卸载。
- 实体调度器的语义：实体退役（玩家下线）后任务被静默丢弃（返回 `false` / `null`）；**Folia 上玩家在区域之间（异步传送、换维度、重生）时到期的一次性任务也会被丢弃**（见已知限制）。重复任务（每个玩家的菜单 tick）在 retired 回调里核对是不是同一个任务，玩家仍在线且还有会话就重新挂。实测同世界远距离 `/tp`、换维度、死亡重生都没有让玩家调度器退役。

### 玩家线程上的会话

- 每个玩家一个 tick 定时器（替代上游的一个全局定时器）；会话表是并发表，但每个玩家的条目只在其所属线程上读写。
- **跨区域重锚**：Folia 的 `/tp` 走 `teleportAsync`、**不触发 `PlayerTeleportEvent`**，旧实体留在另一个区域且 `isValid()` 仍为真。所以每个菜单 / 应用 tick 开头先做 `surfaceMoved` 检查，满足任一条件就在玩家当前位置重建菜单（失败时 20 tick 退避）：菜单的展示 / 交互实体不归当前线程；玩家离菜单平面超过 128 格；仅 Folia：换了世界，或一 tick 内位移超过 8 格（mouse 模式 1 格）。旧实体交回旧区域的线程删除。Paper 上 `/tp` 仍由 `onTeleport` 重锚。
- 实体 id 在生成时缓存（Folia 上 `getEntityId()` 走带线程检查的 `getHandle()`）；`MenuView.valid` = `isValid && owns`。
- mouse 模式摄像机：摄像机不归本线程就重建；SetCamera 包用接收者本人构造再改写实体 id；远距离传送后等客户端追踪到新摄像机再切换。
- 复用旧位置（子菜单、`back`、应用失败回退）前先检查同一世界、`owns(平面原点)`、128 格内，否则在玩家眼前重新取平面。`ApplicationContext.spawnPrivate` 的位置必须归当前区域。
- 26.3 协议：客户端对位置同步只回 `AcceptTeleportation(id, x, y, z, yRot, xRot)`；`SyntheticTeleportLedger` 按字段判断，带转角就当场喂给指针（这是 11-10 基线「mouse 模式右键点不中」的根因，Paper 26.3 同样受影响）。
- 玩家下线：`closeImmediately` 后清该玩家的 meta / 参数、指针待确认 id 与 PosRot 欠账、输入频道缓存。

### 入口

- `/arcmenu …` 由玩家发出但当前线程不拥有该玩家（其它插件在全局 / 异步 / 别的区域线程 `commandMap.dispatch(player, …)`）时，整条子命令跳到玩家线程执行。控制台 `execute as <玩家> run arcmenu …` 的 Bukkit 发送者仍是控制台，ArcMenu 回「请由玩家打开」，与 Paper 一致。
- 菜单声明的自定义命令（`open-commands`）在 `MenuOpenCommand.execute` 里同样转发到玩家线程。
- 事件监听器在事件所在线程上运行，涉及菜单的处理都在玩家线程上。

### 跨玩家动作与软依赖

- `players:` / `all-online-players` 动作：归当前线程的目标照旧当场过滤执行；别的区域的目标，先在源线程展开源玩家的占位符，再跳到目标的线程判断条件并执行，目标线程上不再读源玩家。
- `op` 动作：`setOp` 进全局锁（ops 列表全服共享；服务器自己的 `/op` 不经过这把锁）。
- Vault：存 / 取 / 设余额串行到全局线程（Folia 上下一个全局 tick 执行）。PlayerPoints 不变；条件里的 `%vault_eco_balance%` 仍在区域线程读，取决于 PlaceholderAPI 扩展本身。
- 控制台命令动作（`console-command`）交给全局线程执行（Folia 上只接受在全局区域执行控制台命令，因此推迟到下一个全局 tick；Paper 上当场执行）。

### reload 与文件通道

- `ArcFileChannel`：`submit(work)` 入队（已在通道线程上就当场执行，可重入）；`call(work)` 入队并等待，只给 worker 线程用；`onGlobal(work)` 只能在通道线程上调，把「发布」交给全局区域并等它做完，结果或异常回到通道线程，所以文件事务能在发布失败时回滚。插件停用时等待在 100 ms 内放弃。
- `/arcmenu validate|reload [all]` / `resources`：在通道上读 config / 语言 / tooltip、重建资源、解析校验菜单 / 动画 / 模板，再 `onGlobal` 一次性发布（替换 `@Volatile` 目录引用、`menuCommands.replace`、各项设置、`closeMenus`）。编辑器、workspace、CraftEngine 回调共用同一条「通道上解析 → 全局发布」路径，所以并发进来的 reload 天然串行。与其它插件命令是否冲突，在全局线程的 `replace` 里检查，冲突就不发布。
- `MenuCommandRegistry` 只在全局区域（及 enable / disable）改 `commandMap`，方法加 `@Synchronized`。
- `closeMenus` 先给会话表拍快照，每个玩家线程上只关快照里那个会话：reload 之后用新目录打开的菜单不会被误关。
- 编辑器按玩家并发：每个玩家一个 inbox，保存 / 应用 / 模板动作在通道上执行后回玩家线程 ack，期间后到的包排队（上限 256），顺序不变。
- 命令回复回到发送者线程（玩家 → 玩家线程，控制台 → 全局区域）。

### 共享结构

配置与目录是 `@Volatile` 引用指向不可变快照；只警告一次的集合、编辑器的表用并发集合；计数用 `LongAdder`；`RuntimeStateStore`（meta / 数据 / 参数）仍是整类 `@Synchronized`（见已知限制）。`/arcmenu performance` 把同一线程在同一服务器 tick 里跑的所有玩家 tick 合成一个样本（Paper 上与上游一致；Folia 上是「每个区域 tick」）。

### Paper 上与上游的可见差异

- 每个玩家各一个 tick 定时器；状态异步保存。
- `/arcmenu reload|validate|resources`、编辑器保存 / 应用、workspace 动作的回复晚 1～2 tick（IO 在文件通道上做，主线程不再被大包安装或资源重建卡住）。
- touch 模式开着菜单走出 128 格后，菜单在玩家眼前重建（上游留在原地）。
- `folia-touch-ux` 分支：touch 模式下界面随潜行 / 站起 / 爬行的眼高平移；包层点击按客户端 tick 去重（不再 75 ms 去抖）；菜单开着时副手交互也取消（见 [touch-ux.md](touch-ux.md)）。
- 玩家下线即清 meta（TrMenu 语义；上游留到重启）。
- 应用 API 的 `runLater` / `runTimer` 返回的 `BukkitTask.taskId` 恒为 -1。

## 公开 API 的线程契约

`ArcMenuApi.capabilities()` 含 `ArcMenuCapability.REGION_THREADING` 表示下列契约成立（`apiVersion()` 仍为 1：枚举末尾追加常量对二进制和源码都兼容，fork 自己升版本号会和上游将来的版本撞含义）。与上游的 API 相比只有新增、没有删改（`javap` 对比过）。

- **任意线程可调**：`apiVersion`、`capabilities`、`applications`、`pointerPolicy`、`registerRoute` / `unregisterRoutes`、`registerApplication` / `unregisterApplications`、`close(player)`（不在所属线程时自动转发，离线玩家忽略）、`runFor`、`openAsync`、`openApplicationAsync`，以及 `ArcMenuApplicationHandle` 的所有方法。
- **必须在玩家的所属线程调用**（Paper：主线程；Folia：当前拥有该玩家的区域线程）：其余带 `Player` 参数的方法（`open`、`openApplication` 等），否则抛 `IllegalStateException`，异常信息提示改用 `runFor` / `*Async`。
- **新增方法**（接口 default 方法）：`runFor(player, Consumer<ArcMenuApi>): Boolean`——在玩家的所属线程执行（已在就当场执行），无法调度（玩家离线 / ArcMenu 已停用）时返回 `false`；`openAsync(player, menuId[, args])`、`openApplicationAsync(player, appId[, args])` 返回 `CompletableFuture<Boolean>`，无法调度时以 `false` 完成；`ArcMenuApplicationContext.track(ScheduledTask)`——会话关闭时取消一个 Paper / Folia 调度器任务。
- **回调**：路由、`ArcMenuApplication.open`、会话回调在其玩家的所属线程上执行并随玩家跨区域；同一玩家串行，**Folia 上不同玩家并发**，会话之间共享的状态要自己保证线程安全。
- **`ArcMenuApplicationContext`**：`isActive`、`execute`、`track` 任意线程可调（`execute` 在调用方已拥有玩家时同步执行，无法调度时返回 `false`），其余只能在玩家线程调。`spawnPrivate` 的位置必须在玩家当前所在区域内，否则 `IllegalArgumentException`。`runLater` / `runTimer` 走实体调度器（Paper 上在 tick 的实体调度阶段执行），返回的句柄只支持 `cancel()` / `isCancelled()`，task id 恒为 -1，玩家已下线时生来就是已取消；需要真实句柄时自己在 `player.getScheduler()` 上调度再交给 `track`。
- **注销**：`unregisterApplications` / handle 注销先从注册表删除、再关闭会话；会话创建时、`open()` 回调后、每 tick 都复查注册 token，所以注销后不会有会话再被打开或 tick。

```java
if (arcMenu.capabilities().contains(ArcMenuCapability.REGION_THREADING)) {
    arcMenu.openApplicationAsync(player, "myplugin:shop")
        .thenAccept(opened -> { if (!opened) getLogger().info("not opened"); });
} else {
    player.getScheduler().run(plugin, task -> arcMenu.openApplication(player, "myplugin:shop"), null);
}
```

## 不支持热重载 / PlugMan

Folia 本身不支持 `/reload`。用 PlugMan 之类在运行中卸载 / 重新加载 ArcMenu **不受支持**：插件停用后不能再调度任务，正在别的区域里的菜单实体没法从正确的线程删除（这些实体不持久化，区块卸载时消失）。要换 jar 请重启服务端。ArcMenu 自己的 `/arcmenu reload`（重新读配置和菜单）是支持的。

## 已知限制

- `RuntimeStateStore` 仍是整类 `@Synchronized`：Folia 上各区域线程读写 meta / 数据 / 参数时争同一把锁，玩家多时是热点。
- `ItemActions` 给头颅设主人时调用 `getOfflinePlayer(name)`，按名字查档案可能阻塞，且在区域线程上执行。
- Folia 上 `ArcScheduler.later` / `execute` 排的一次性任务，如果在玩家跨区域途中（异步传送、换维度、重生）到期会被 Folia 丢弃，例如动作链里的 `delay`、BossBar 定时隐藏；没有逐个补 retired 回调。
- 控制台命令动作在 Folia 上推迟到下一个全局 tick 执行（上游在当前 tick），与后续动作的先后可能变化。
- Paper 上 touch 模式开着菜单走出 128 格会重建菜单（见上）。
- **未在游戏内实测**：编辑器协议（保存 / 应用 / 模板，需要 Fabric 编辑器客户端）、workspace 的全部动作、市场下载；CraftEngine 的「初始目录待定」路径；PlaceholderAPI / Vault / PlayerPoints；区域合并分裂、多人同时开菜单；1.21.x / 26.1–26.2 的服务端（只有编译与单元测试）。
- Folia 不支持热重载 / PlugMan（见上）。
- 测试客户端没装 ArcMenu 资源包时，mouse 模式 tooltip 的九宫格皮肤显示成白框（字形缺失），与 Folia 无关。

## 与上游的差异

- 调度：`ArcScheduler` 取代全部 `BukkitScheduler` / `isPrimaryThread` 用法；按玩家实体调度器 tick；会话表并发化；状态异步保存；文件动作统一走单线程文件通道、在全局区域发布。
- 跨区域重锚、入口跳线程、跨玩家动作、26.3 `AcceptTeleportation` 转角（Paper 26.3 也受益）。
- API：`REGION_THREADING`、`runFor` / `openAsync` / `openApplicationAsync` / `track(ScheduledTask)`、注销先删后关与 token 复查、KDoc 线程契约。
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

### 合并后的验证（11-10）

- 缺省基线 `mvn clean verify`（JDK 21）：protocol 11、core 82、api 2、paper 163 个测试通过（1 个上游原有的跳过）。
- 版本矩阵 `scripts/test-paper-version-matrix.sh -k`：1.21.1 / 1.21.4 / 1.21.6 / 1.21.11（JDK 21，ProtocolLib 5.4.0）、26.1.2 / 26.2 / 26.3（JDK 25，ProtocolLib 5.5.0-SNAPSHOT）7 项全部 PASS，每项 258 个测试（1 跳过），JOML 检查全 OK。分支文档里「1.21.1 编译不过」的问题已由 `ArcScheduler` 的反射修掉。
- `check-joml-compat.py`：缺省基线 jar 与 26.3 API jar 都是 90 个 `org.joml` 引用在 1.10.5 / 1.10.8 / 1.10.9 里全部能解析。
- 游戏内（测试环境与脚本在仓库外的 `arcmenu/` 目录：`run-regression.sh`、`scenarios/`）：缺省基线 jar 在 Folia（Shiroha 26.3）与 Paper 26.3 build 246 上各跑 18 个带断言的场景——`arc-a1-touch` / `-quit-check` / `-mouse-teleport`、`arc-menus`、`folia-build-arc-smoke`、`folia-runtime-tp-touch` / `-tp-mouse` / `-walk` / `-dimension` / `-death` / `-foreign-thread`、`folia-reload-api-reload` / `-storm` / `-race` / `-race-dense` / `-race-baseline` / `-stall` / `-ce`（带 CraftEngine 26.10-SNAPSHOT）——36 次全过。对 26.3 API 编译的 jar 也在两服正常加载，`folia-build-arc-smoke`、`arc-menus`、`arc-a1-mouse-teleport` 两服都过——两种 jar 在 26.3 上都能用，发布用缺省基线 jar 即可。
- JFR（只抓异常）：两服所有阶段里带 ArcMenu 栈帧的异常中，没有 `Thread failed main thread check`、跨区域 `IllegalStateException`、`UnsupportedOperationException` 或 `NoSuchMethodError`（只有 JVM 方法句柄链接时内部吞掉的 `…$Holder` 探测、插件类加载器的 `ClassNotFoundException` 探测、`createDirectories` 等内部捕获的文件异常）；服务端日志里这些关键字均为 0。
