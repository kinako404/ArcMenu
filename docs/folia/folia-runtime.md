# folia-runtime：核心运行时的 Folia 适配

分支 `folia-runtime`（基于 `folia` 7580796）。编号对应 `/tmp/arcmenu-folia/` 的摸底报告：`audit-runtime.md` 记作 R§x，`audit-input-render.md` 记作 B1…B7。
所有改动在普通 Paper 上都退化成“主线程当场执行”：`ArcScheduler.owns(...)` 在 Paper 上等于 `isPrimaryThread()`，菜单 tick 本来就在主线程，所以新加的归属检查恒为真。

## 新增的调度辅助（`platform/ArcScheduler.kt`）

- `owns(location: Location)`：Folia 上 `Bukkit.isOwnedByCurrentRegion(location)`，Paper 上 `isPrimaryThread()`。
- `ownsGlobal()` 不再直接调 `Bukkit.isGlobalTickThread()`（1.21.4 API 才有，对 1.21.1 编译不过），改成反射：先找 `Bukkit.isGlobalTickThread`，再找 `RegionizedServer.isGlobalTickThread`，都没有时退回 `isPrimaryThread()`（`globalTickMethod`，有单元测试）。
- `remove()`：实体不在本区域且插件已停用（Folia 热卸载）时直接放弃，不再硬删后抛 ISE（B4 的一半）；非持久实体留给区块卸载。
- KDoc 写明：实体任务在实体调度器退役时被静默丢弃；Folia 上玩家在区域之间（异步传送、换维度、重生）时到期的任务也会被丢弃，重复任务要在 retired 回调里自己重新挂。

## ① P0 跨区域（R§2.2(c)(d)(e)、B2、B3）

根因（11-10 基线“Folia 上同世界远距离 /tp 后菜单丢失”）：Folia 的 `/tp`（`TeleportCommand` → `TeleportUtils.teleport` → `Entity.teleportAsync`）**不触发 `PlayerTeleportEvent`**（Shiroha 26.3 `TeleportCommand.class` 字节码可见），`onTeleport` 的 1 tick 后重锚根本不会排上；旧实体还活着（`isValid` 只读字段），tick 的失效检查也不成立，菜单就一直留在 2600 那个区域。

另外按 `FoliaEntityScheduler.EntityScheduledTask.accept` 的源码，重复任务执行时若 `entity.isRemoved()` 为真（玩家异步传送途中被 `removePlayerImmediately(CHANGED_DIMENSION)`）会被当成退役、跑 retired 回调后取消。实测（r1，带临时诊断日志）同世界远距离 tp、换维度、死亡重生都**没有**出现这种退役，但仍按防御处理（见下）。

改法：
- `MenuSessions.ensureTicker`：retired 回调只删同一个任务（`tickers.remove(uuid, task)`，P2 那条也在这），玩家仍在线且还有会话时**重新挂定时器**。
- `tickSession` / `tickApplication` 开头新增 `surfaceMoved(...)`，满足任一条件就立即重锚（失败时按 20 tick 退避，`reanchoredAt`）：
  - 菜单的展示实体或交互实体还活着但不归当前线程（`MenuView.misplaced`、`ArcScheduler.owns(capture)`）；
  - 玩家离菜单平面超过 `RUNTIME_REANCHOR_DISTANCE` = 128 格（touch 模式走远）；
  - 仅 Folia：换了世界，或者一 tick 内位移超过 8 格（mouse 模式 1 格，mouse 模式下移动包全被吞，任何位移都只能是传送）——补上 Folia 不发 `PlayerTeleportEvent` 的缺口。
- `MenuView.valid` 改为 `isValid && ArcScheduler.owns(it)`；实体 id 在 `spawn` 时缓存进 `IdentityHashMap`，`matrix` / `removeDisplay` / `configureCursor` / `refreshDepthEye` 不再调 `getEntityId()`（Folia 上它走带线程检查的 `getHandle()`）；头颅回调的 `current` 加 `owns(display)`。
- 鼠标摄像机：`CursorState` 缓存 `cameraId`；`ProtocolPointerBridge.setCamera(player, id)` 用接收者本人（总归当前线程所有）走 `ClientboundSetCameraPacket(Entity)` 构造器（构造器缓存一次），再把整型字段改写成摄像机 id——ProtocolLib dev-build 无法空构造这个包（实测 `PacketContainer(CAMERA)` 抛 ISE/NPE），所以不能直接 `new`。`hasValidCamera` 与 `begin()` 的复用条件加 `owns(camera)`，摄像机不归本线程时一律重建，不再对旧摄像机发包。
- 远距离传送后重建摄像机时，目标区块可能还没发到客户端，客户端会忽略指向未知实体的 SetCamera（r2 实测：Folia 上传送后菜单在、画面却还停在旧摄像机）。`PointerController.reanchor` 在玩家尚未追踪新摄像机（`player !in camera.trackedBy`）时把切换挂起（`cameraPending`），由每 tick 的 `poll()` 等到追踪后再发 SetCamera + 隐藏手 + 重置转角。Paper 上通常立即追踪，行为不变。
- 复用旧位置开子菜单 / 应用（`routePlacement`、上一个会话的平面、`back`、应用失败回退）：`createSession` / `createApplication` 先用 `usable()` 检查（同一世界、`owns(平面原点)`、128 格内），否则在玩家眼前重新取平面。
- `ApplicationContext.spawnPrivate`：位置不归当前区域时 `require` 失败，报“私有实体位置不属于玩家所在的区域线程（Folia），请在玩家附近创建”，不再让 Folia 抛 off-region add entity。

Paper 上的行为：归属检查恒真；`/tp` 仍由 `onTeleport` 重锚（与上游一致），跳变检测只在 Folia 开。可见变化有两处：touch 模式开着菜单走出 128 格后，菜单会在玩家眼前重建（上游是留在原地）；玩家下线时清 meta（见 ⑤）。

## ② P1 入口线程（R§2.4、R§2.7）

- `ArcMenuPlugin.onCommand` 开头：发送者是玩家而当前线程不拥有该玩家时（别的插件在全局 / 异步 / 别的区域线程上 `commandMap.dispatch(player, …)` 或直接调 `Command.execute`），`ArcScheduler.execute` 跳到玩家线程再执行整条子命令（覆盖 open / close / mode / animate / pointer 等所有以玩家为对象的子命令）。实测控制台 `execute as <玩家> run arcmenu …` 的 Bukkit 发送者仍是控制台（见 ⑦），不会走到这里；`Bukkit.dispatchCommand(player, …)` 在 Folia 上会被服务端自己的 `ensureTickThread` 拒绝。
- 自定义菜单命令（`open-commands`）：没有改 `MenuCommandRegistry.kt`（归 folia-reload-api），而是在 `ArcMenuPlugin` 构造它的 opener 回调里先跳玩家线程再 `openRoot`。
- Paper：命令本来就在主线程，`owns` 为真，当场执行。

## ③ P1 跨玩家动作与软依赖（R§2.3、R§2.6）

- `ActionExecutor`：`players:` / `all-online-players` 的目标里，归当前线程的玩家照旧在源线程先按条件过滤（Paper 上等于上游）；别的区域的玩家，**源玩家的占位符在源线程先展开**（`sourceExpansions(action, expand)` 列出每种动作会展开的字符串，有单元测试），跳到目标线程后再在那边判断 `players:` 条件并执行。`executeExpandedAction` 改为接收展开函数，不再在目标线程上读源玩家。
- `OpCommandAction`：`setOp` 进全局锁 `OP_LOCK`（ops 列表是所有区域共享的）。服务器自己的 `/op` 不经过这把锁，这点无法控制。
- `ExternalBalanceBridge`：Vault 存 / 取 / 设余额走 `ArcScheduler.global`，Folia 上串行到全局线程（下一个全局 tick 执行），Paper 上当场执行。返回值本来只用来打一次“缺少提供者”的警告，所以 `apply` 改成无返回值、在执行处报告。PlayerPoints 不动。条件里的 `%vault_eco_balance%` 仍在区域线程读，取决于 PAPI 扩展。

## ④ 26.3 协议 B1

- 26.3 客户端收到位置同步只回 `AcceptTeleportation(id, x, y, z, yRot, xRot)`、不再补发 PosRot。新的 `SyntheticTeleportLedger`（`ProtocolPointerBridge.kt`）按字段判断：accept 带两个 float 就当场把转角喂给 `consumeRotation`、不记“欠一个 PosRot”；不带（≤26.2）走旧逻辑。这样鼠标模式光标能动，`syntheticResponses` 不再无限增长、也不会在退出后吞掉玩家真实的 PosRot。
- 结论：11-10 基线里四个组合都有的“mouse 模式右键点不中”就是 B1（不是测试客户端 `look` 驱动不了）。修后 `arc-a1-mouse-teleport` 在 Folia 与 Paper 26.3 上都通过（`look -14 0` 后右键命中 card-area，截图里光标在 card 上、出现 tooltip）。

## ⑤ P2

- `@Volatile`：`MenuSessions.tooltipStyles` / `runtimeScreen`、`ArcMenuPlugin.shortcutSettings`、`PlaceholderResolver` 的 PAPI 反射缓存（顺带把“先置已尝试再赋值方法”的竞态改成先赋值后置标志）、`PerformanceMetrics.startedAt`。
- 只警告一次的集合换 `ConcurrentHashMap.newKeySet()`：`ActionExecutor.warnedSounds` / `warnedNumericExpressions`、`ExternalBalanceBridge.warnings`。
- 定时器 retired 回调核对同一任务：见 ①。
- 玩家下线：`RuntimeStateStore.forget` 清掉该玩家的 meta 与参数（`MenuSessions.forget`，在 `onQuit` 里 `closeImmediately` 之后调用）；`PointerController.discard` 调 `ProtocolPointerBridge.forget` 清待确认 id 与 PosRot 欠账（B6），并清 `MenuInputBridge` 的频道缓存。注意：meta 下线即清是 TrMenu 的语义，上游是留到重启，Paper 上也生效。
- B5：`MenuInputBridge` 在玩家线程（每次 `state()`）把“是否监听 `arcmenu:menu_input`”记进并发集合，Netty 线程的 `consumeInput` 只读这个集合（`listening`），不再碰 `player.listeningPluginChannels`。
- B7：`PointerController.end()` 把 `check(mayTouch)` 提到最前面。
- `/arcmenu performance`：新增 `recordSessionTick`，同一线程在同一服务器 tick（`Bukkit.getCurrentTick()`）里跑的所有玩家 tick 合成一个样本，`ticks` / 平均 / 最大值回到上游“一个定时器一次 tick”的含义（Paper 完全一致；Folia 上是“每个区域 tick”）。`reset()` 用代号丢弃各线程尚未结算的样本。每个线程最后一个 tick 要等下一次 tick 才计入。
- JOML `Quaternionf.dot` 的 26.3 `NoSuchMethodError`：folia-build（`f708717`）已修，本分支不重复改，避免集成时冲突。

## ⑥ `ArcScheduler.kt:27`

见“新增的调度辅助”：`isGlobalTickThread` 走反射，现在对 Paper API 1.21.1 编译通过（`mvn -o package` 全绿）。

## ⑦ 实测结论（Shiroha 26.3）

- 玩家实体调度器在**死亡重生、换维度（overworld↔nether）、同世界远距离 /tp** 时都**不退役**：r1 用临时诊断日志（retired 回调里打 INFO，已删）跑 tp / 换维度 / 死亡三个场景，一次退役都没有，重锚都在新区域线程上执行（`/tmp/fr/r1-folia/server-full.log` 的 `[diag-fr]` 行）。retired 回调里的“仍在线就重新挂”保留作防御。
- 跨区域改展示实体：探针 FrProbe（`/tmp/fr/probe`，场景 `folia-runtime-xregion-probe`）在玩家实体调度器上每 20 tick 改一次脚下 TextDisplay 的文字，玩家 tp 到 6000 后每次都抛 `IllegalStateException: Thread failed main thread check: Accessing entity state off owning region's thread`（`CraftTextDisplay.getHandle`），Folia 记 `Entity task for … generated an exception`，但**定时器不取消**（run 计数一直到 12），且 `isValid()` 仍为 true（只读字段）——所以只看 `isValid` 的旧检查拦不住（`/tmp/fr/r3-folia/server.log`）。
- `execute as ArcBot run arcmenu open`（控制台）：Bukkit 命令的发送者仍是控制台，ArcMenu 回“请由玩家打开运行菜单”，与 Paper 相同，不会以玩家身份跑到错误线程。能以玩家身份在别的线程进来的是插件直接 `commandMap.dispatch(player, …)`（`Bukkit.dispatchCommand` 在 Folia 上会先被 `ensureTickThread` 拒绝）。

## 验证

单元测试（先写失败测试再实现）：新增 `SyntheticTeleportLedgerTest`（3）、`ArcSchedulerTest`（1）、`PerformanceFrameTest`（2）、`CrossRegionRuntimeTest`（3）。`mvn -B -o package -pl paper -am`：protocol 11、core 82、api 2、paper 146 个测试全过（1 跳过，上游原有）。

游戏内（`/tmp/fr/session.sh` 持锁换 jar、挂 JFR、跑场景、停服还原；截图都看过）。新场景在 `arcmenu/scenarios/`，前缀 `folia-runtime-`：

| 场景 | 内容 | Folia | Paper 26.3 |
|---|---|---|---|
| `tp-touch` | touch 开着菜单同世界 /tp 到 6000、在落点点中 card、回原地无残留、sessions=0 | 过（r1、r3） | 过（r2） |
| `tp-mouse` | mouse 开、右键 card，再 /tp 到 6000（保持 yaw）、落点仍命中 card、两处无残留 | 过（r3；r2 暴露摄像机未追踪问题后修） | 过（r2、r3） |
| `walk` | touch 开着菜单疾跑约 250 格，130 格内有菜单实体、runtime=1，关后 300 格内无残留 | 过（r1） | 过（r2） |
| `dimension` | overworld→nether→overworld，每次落地菜单重建并可点击，关后两边无残留 | 过（r1） | 过（r2） |
| `death` | 开着菜单 /kill、重生后菜单在出生点重建，关后无残留 | 过（r1） | 过（r2） |
| `foreign-thread` | 控制台 `execute as`（不应打开）；FrProbe 在全局线程 dispatch `arcmenu open` / `arc`（自定义命令）、在异步线程 dispatch `arcmenu close` | 过（r2） | 过（r2） |
| `xregion-probe` | ⑦ 的 Folia 行为探针，无断言 | 见 ⑦ | — |

回归：`arc-a1-touch`、`arc-a1-quit-check`、`arc-a1-mouse-teleport`（基线四个组合全失败，现在通过）、`arc-menus` 在 Folia（r2）与 Paper（r2b、r3）全过。
JFR（`/tmp/fr/r*-*/exc-sum.txt`）：ArcMenu 栈帧下没有 `Thread failed main thread check` / 跨区域 ISE；服务端日志里该报错只来自 FrProbe 探针（r3 共 9 次）。剩下的是插件类加载器的 `ClassNotFoundException`、JVM 方法句柄链接时内部吞掉的 `IncompatibleClassChangeError` / `NoSuchMethodError(…Holder…)`、ProtocolLib 内部 `DefaultInstances` 的 NPE，均与本改动无关。
截图里 mouse 模式 tooltip 的九宫格皮肤显示成白框，是测试客户端没装 ArcMenu 资源包（字形缺失），与本分支无关。
r1 的 dimension / death / walk 用的是加了诊断日志、尚未改摄像机包的 jar（这三个场景都是 touch 模式，不走摄像机路径）；其余都是最终代码（诊断日志已删）。
注：`run-scenario.sh` 在场景没有截图时会因 `set -e` + `pipefail` 提前退出、不生成 `scenario.txt` / `server.log`（`foreign-thread` 的输出目录只有 `client.log`），不影响退出码，脚本不归本分支，没改。

## 没做 / 留给后续

- `RuntimeStateStore` 的热点锁（R§2.5）没改，仍是 `@Synchronized`。
- `ConsoleCommandAction` 在 Folia 上推迟到下一个全局 tick 的语义（R§2.3）没有改，只在这里记一笔。
- `ItemActions.getOfflinePlayer` 阻塞（R§2.8）没改。
- `ArcScheduler.later/execute` 的一次性任务在玩家跨区域途中到期仍会被 Folia 丢弃（例如动作链里的 `delay`、BossBar 定时隐藏），没有逐个补 retired 回调。
