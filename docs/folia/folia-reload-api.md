# folia-reload-api：reload 编排 / 编辑器 / workspace / API 的 Folia 适配

基线：`folia`（7580796）。编号对应 `/tmp/arcmenu-folia/` 下的摸底报告：audit-runtime（P1 = §2.4 reload、§2.7）、audit-pack-resource（§2、§3）、audit-editor-api（E*、W*、A*）。

## 总体模型

- **文件通道** `platform/ArcFileChannel.kt`（新文件，不改 `ArcScheduler`）：单线程 `ArcMenu-files`。凡是读写 `plugins/ArcMenu/`、解析校验候选目录、重建资源包、重载模板的动作都排进这一条队列，所以并发进入的 reload 天然串行。
  - `submit(work)`：入队，已在通道线程上时就地执行（可重入，不会自锁）。
  - `call(work)`：入队并等待结果，只给 worker 线程（市场下载 / 发布）用，tick 线程绝不调用。
  - `onGlobal(work)`：只能在通道线程上调用，把“发布”交给全局区域（Paper 上是主线程）并等它做完；结果或异常回到通道线程，所以 workspace / 编辑器的文件事务仍能在发布失败时回滚。插件停用时等待会在 100 ms 内放弃，不会卡住关服。
- **发布**（全局区域）：`menuCommands.replace`（改 commandMap）、`catalog` / `animationCatalog` 的 `@Volatile` 引用替换、reload 时的 tooltip / 屏幕 / 指针 / 快捷键设置与 `configureMetrics`、`sessions.closeMenus()`。
- **回投**：命令回复回到发送者线程（玩家 → 玩家实体调度器；控制台 → 全局区域）；编辑器 ack、workspace reply、`editor.refreshSnapshot` 回到玩家线程。
- **Paper 上**：全局区域就是主线程，所以发布、关会话、改命令表的顺序和上游一样。可见的区别只有一点：`/arcmenu reload|validate|resources`、编辑器保存 / 应用、workspace 动作的回复要晚 1～2 tick 才到（IO 不再在主线程上做，所以主线程也不会被大包安装或资源重建卡住）。

## 逐条

| 编号 | 改了什么 / 为什么 | Paper 上 | 验证 |
|---|---|---|---|
| runtime P1 / pack-resource §2.2 / E2 | `/arcmenu validate|reload [all]`、`resources` 改为 `fileCommand`：在通道上读 config、语言、tooltip，重建资源，解析校验菜单 / 动画 / 模板，再用 `onGlobal` 一次性发布（`ArcMenuPlugin.reloadCatalog`）。`applyEditorCatalog`（编辑器 / workspace / CE 共用）同样是“通道上解析 → 全局发布”。在通道线程上校验菜单集合时只做纯计算（`validateMenuSetOffThread` = `MenuSetValidator` + `MenuEntrypointCompiler.compile`）；与其它插件命令是否冲突，在全局线程的 `replace` 里再检查，冲突就不发布，并回复 validation-failed。onEnable 的同步加载保持不变 | 顺序与上游一致，回复晚 1～2 tick | Folia / Paper 场景 reload、storm |
| E3 / pack-resource §2.1 | `catalog`、`animationCatalog`、`shortcutSettings`、`initialCatalogPending` 加 `@Volatile` | 无变化 | 编译 |
| runtime §2.7 / pack-resource §2.3 | `MenuCommandRegistry`：`validate/replace/close` 加 `@Synchronized`，`current` 加 `@Volatile`；类 KDoc 写明 commandMap 只在全局区域 / enable / disable 时改。所有调用点都已经在全局线程上。`MenuOpenCommand.execute` 在调用线程不拥有该玩家时（`execute as`、其它插件 dispatch）转发到玩家线程 | 恒在主线程，等价 | storm 场景 |
| pack-resource §2.4 / runtime §2.2(f) | `MenuSessions.closeMenus` 先给 `sessions` / `applicationSessions` 拍快照，每个玩家线程上只在对象仍是快照里那个时才关；reload 之后用新目录打开的菜单不会被误关，相关的 history / ticker 也不会被清掉 | 主线程当场执行，与上游一致 | reload 场景（reload 后立刻重开、40 tick 后仍开着） |
| pack-resource §3 CE 回调 | `onCraftEngineReloadReady` 在全局线程上触发后，只把 `applyEditorCatalog` 排进通道；`initialCatalogPending` 在通道上复查 | 同 | 见“CE 实测” |
| E1 | `EditorService` 去掉整类 `@Synchronized`，各表换成 `ConcurrentHashMap`；抢写权限用 `writers.putIfAbsent`。每个玩家一个 inbox：保存 / 应用 / 存模板 / 删模板走 `fileOperation`（在通道上执行 `draft.save` + `applyCatalog` 或模板文件事务，然后回玩家线程 ack）；处理期间这个玩家后到的包在 inbox 里排队（上限 256），做完后按顺序继续处理，所以草稿不会被两个线程同时碰，客户端看到的 ack 顺序也不变。`createMenu`（仓库内没有调用方）改为返回 `CompletableFuture<String>` | 包顺序不变，ack 晚 1～2 tick | 编译 + 现有 EditorDraft 测试；**没有编辑器客户端，未实测** |
| E5 / W9 / core §5 | 编辑器的菜单保存、模板增删，workspace 的 create/configure/delete/import/install/uninstall/export/pack-*，`/arcmenu reload` 的 `TemplateCatalog.reload`，全部在同一条通道上，不会再同时写 `menus/`、`templates/` | 同 | ArcFileChannel 单元测试（串行、不重叠） |
| W1 | `listing()` 拆成 `listingFiles()`（通道上读 pack 注册表 / 导出目录）和 `listing(player, …)`（玩家线程补 capabilities / resourcePending）。区域线程不再等 `fileLock`，这把锁已经删掉 | 主线程不再做这些 IO | 编译；未实测（需编辑器客户端） |
| W2 | create/configure/delete/import/uninstall/pack-*/preview-open/preview-chunk 都走 `fileAsync`（通道 → 玩家线程）；install 是 worker 下载 → `files.call { install }` → 玩家线程 | 安装大包、重建资源包不再卡主线程 | 同上 |
| W3 | 控制台 `download pack`：下载在 worker 上，install 在通道上，全局线程只负责发通知（原来用 `globalLater` 把解压 + 重建放在全局区域上执行） | 同 | 未实测（离线环境连不上市场） |
| W4 | `finish()`：回投失败（插件停用、玩家下线、实体已 retired，`execute` 返回 false）时，结果是 `Path` 就删掉；install 的临时文件在 worker 的 `finally` 里删 | 同 | 代码审查 |
| W5 / W6 | `previewScene` 读文件、`previewChunk` 追加写 / ImageIO 校验挪到通道（同一玩家的分块在单线程上保持顺序） | 同 | 未实测 |
| W7 / W8 / E4 / E6 / E7 | 不改（摸底结论是正确或不归本路） | | |
| A1 | `ApplicationRegistry` 增加 token 索引（`contains` O(1)、`unregister(token)` 只会成功一次）。注销顺序改为：先从 registry 删掉、拿到被删的 registration，再 `closeApplicationRegistrations(removed)` 关会话；history 按“被删 id 且当前未重新注册”过滤。`createApplication` 在会话放进表之后、`open()` 回调之后各复查一次 token，`tickApplication` 每 tick 复查一次，不在就按 OWNER_DISABLED 关闭 | 单线程下不会触发，行为等价 | 单元测试 + FrlProbe race 场景 |
| A2 | 保持 `apiVersion() = 1`，在 `ArcMenuCapability` 末尾**追加** `REGION_THREADING`。理由：枚举追加常量对二进制和源码都兼容；`apiVersion` 是上游的全局版本号，fork 自己升到 2 会和上游将来的 2 撞含义，而且会让写了 `== 1` 的调用方失效；capability 本来就是上游为“按特性探测”设计的机制 | — | 单元测试、javap |
| A3 | KDoc：`ArcMenuApi` 列出任意线程可调和必须在玩家线程调用的方法；回调同一玩家串行，不同玩家在 Folia 上可能并发；会话跟随玩家跨区域。`ArcMenuApplication` / `ArcMenuApplicationSession` / `ArcMenuApplicationContext` 同步补上 | — | — |
| A4 | `close(player)`：任意线程可调，不在所属线程时自动转发，离线玩家直接忽略。新增 JVM default 方法 `runFor(player, Consumer<ArcMenuApi>): Boolean`、`openAsync(player, id[, args])`、`openApplicationAsync(player, id[, args])`（`CompletableFuture<Boolean>`，无法调度时 complete(false)）。返回值类方法仍然快速失败，异常信息里提示改用 runFor / openAsync | 主线程上 runFor 当场执行 | 单元测试 + FrlProbe asyncopen/asyncclose |
| A5 | 新增 `ArcMenuApplicationContext.track(ScheduledTask)`（default 方法，会话关闭时 cancel）。第三方需要真实任务句柄时，自己在 `player.scheduler` 上调度再交给 track；`runLater/runTimer` 的 KDoc 写明 id 恒为 -1、只能 cancel()、玩家已下线时返回的句柄生来就是已取消、执行时机跟随实体调度器 | — | 单元测试 |
| A6 / A7 | `spawnPrivate` 需要位置在玩家当前区域内、`execute` 在所属线程上同步执行：只写进 KDoc。真正的 `check` 在 `MenuSessions` 的 ApplicationContext 里，属于 folia-runtime | — | — |

二进制兼容：`javap -p` 对比上游 api jar（`/tmp/arcmenu-folia/audit-editor-api/sig-main.txt`），本分支只有新增条目、没有删改：5 个 default 方法及其 `DefaultImpls` / `$jd` 桥、`track(ScheduledTask)`、`REGION_THREADING`。

## 新增的调度辅助

没有改 `ArcScheduler`，也没有给它加方法。新加的是 `platform/ArcFileChannel.kt`（只依赖现有的 `ArcScheduler.global`）。

## 涉及 folia-runtime 文件的地方（集成时注意）

- `MenuSessions.kt`：`closeMenus` / `closeMenusFor`，`createApplication` 里两处 token 复查，`tickApplication` 一处，`closeApplicationRegistration(token)` 换成 `closeApplicationRegistrations(removed)`。
- `ArcMenuPlugin.kt`：`ArcMenuApiImpl` 的构造参数多了 plugin；`EditorService` / `WorkspaceService` 的构造参数多了 files。

## 构建与测试

- 分支编译基线仍是 Paper API 1.21.1。`folia` 上的 `ArcScheduler.kt:27`（`Bukkit.isGlobalTickThread`）对 1.21.1 编译不过，归 folia-runtime / folia-build 处理，所以本分支一律用 `-Dpaper.version=26.3.build.246-stable` 构建和测试。
- `mvn -o -pl paper -am test -Dpaper.version=26.3.build.246-stable`：152 个测试通过（1 个跳过）。新增 15 个：`ArcFileChannelTest` 8 个、`ArcMenuApiDefaultsTest` 5 个、`ApplicationRegistryTest` 2 个。

## 实测

场景都放在 `arcmenu/scenarios/`，文件名前缀统一为 `folia-reload-api-`：

- 场景：`-reload`、`-storm`、`-race`、`-race-dense`、`-race-baseline`、`-stall`、`-ce`。
- 辅助文件：`-frl-probe.yml`（测试菜单，带自定义命令）；`-probe/`（测试插件 FrlProbe 的源码，以及生成 9 张 2048² 噪声 PNG 的 `GenBigImages.java`）；`-session.sh`。
- `-session.sh` 的流程：整段持有 `/tmp/arcmenu-server.lock`（用 `ARC_LOCK_HELD=1` 调 `run-scenario.sh`）；把服务端 `plugins/` 里原有的 jar 和数据目录挪开；放入被测 jar 起服；跑场景；停服；恢复 `plugins/` 原状。

截图都亲自看过：reload 之后重开的 example 菜单、自定义命令打开的 FRL probe 菜单、storm 结束后 `/frlfinal` 打开的菜单，Folia 和 Paper 上都正常。

| 场景 | Folia（Shiroha 26.3） | Paper 26.3-246 |
|---|---|---|
| reload：开着菜单 `/arcmenu reload` → 菜单被关、回复 Loaded；随后重开的菜单 40 tick 后仍开着；`reload all` / `validate` / `resources` 都有回复；自定义命令 `/frlprobe` 能开菜单；最后 sessions=0 | 过（跑了 2 轮）：`shots/1110-114100-folia-…-reload`、`1110-114523-…` | 过：`shots/1110-115738-paper-…-reload` |
| storm：玩家 16 次 `/arcmenu reload` + `/frlprobe` + `/frlstorm`，同时控制台约 66 次 reload，并且在两次 reload 之间来回改 open-commands；最后改成 `[frlfinal]` | 过：68/68 次控制台 reload 都回复“已加载”；`/frlfinal` 能开菜单；`/frlprobe` 报 Unknown command；sessions=0。`shots/1110-114216-folia-…-storm`、`1110-114651-…` | 过（控制台 67 次）：`shots/1110-115854-paper-…-storm` |
| race：异步线程 注册 → 玩家线程打开 → 随机等待 → 注销，120 轮 ×2；外加 asyncopen / asyncclose、caps | 过：opened=closed、active=null、RACE-OK；`openAsync` 返回 ok=true、菜单打开；异步线程 `close` 成功；caps 显示 version=1、region=true | 过（RACE-OK，opened=closed） |
| race-dense（tickrace：同一 tick 里玩家线程打开、全局区域注销，150 轮 ×2） | WIP 基线：79/79 个会话在注销后仍收到 onTick（TICKRACE-LEAK）。本分支：两轮 zombies=0（121、126 个会话），sessions=0 | 过：Paper 上全局任务先于实体调度器执行，打开时应用已经注销，sessions=0、zombies=0（单线程下不存在这个竞态） |
| stall：9 张 2048² 噪声 PNG（约 130 MB），`/arcmenu reload all` 发出后 1 tick 就发 `/arcmenu status` | 本分支：status 当场回复，Loaded 在之后才到，images=15。WIP 基线：**失败**，status 的回复被重建资源包卡在 Loaded 之后（区域线程被阻塞） | 本分支：过（主线程也不被阻塞） |
| ce：装 CE 26.10-SNAPSHOT，开着菜单执行 `/ce reload all`，然后 `/arcmenu reload all`，再重开菜单 | 过：CE 异步线程两次打印“已向 CraftEngine 注册外部资源包”；菜单照常打开；sessions=0。CE 用完已拿掉 | 未测 |

- 服务端日志都留在 `/tmp/frl/server-*.log`。所有轮次里唯一的异常是已知的 `NoSuchMethodError: Quaternionf.dot`（JOML，folia-build 的 f708717 已修），没有 `Thread failed main thread check`、IllegalState 或 UnsupportedOperation。
- race、stall 等场景的退出码显示为 1，是因为这些场景不截图，`run-scenario.sh` 里拷贝截图的 grep 在 pipefail 下返回 1；客户端日志里都是 PASSED。
- race-dense 在 Folia 上第二轮的 expect 顺序原先写错了（放在了 status 那次 dump 后面），现已改正；两轮的 dump 都是 TICKRACE-OK。
- race 场景在 WIP 基线上也是 RACE-OK：那个窗口太窄，打不中。能把 A1 打出来的是 race-dense 的 tickrace 模式。
- 基线 tickrace 的 zombie 指的是“注销之后仍然被 tick”的会话，并不是永久残留：基线里关闭被投递到下一 tick，期间仍会回调 onTick，结束时 active=null。本分支在 tick 前复查 token，所以这种回调为 0。

## 没做 / 未实测

- 编辑器协议（保存 / 应用 / 存模板、删模板时的 inbox 排队）和 workspace 的全部动作（create / install / import / 预览分块等）：没有 Fabric 编辑器客户端（`FENTAIIII/ArcMenu-Editor`），只有编译和现有单元测试覆盖，W1–W6 未在游戏里实测。
- 市场下载（W3）：离线环境连不上市场，未测。
- CE 的“初始目录待定”路径（`initialCatalogPending`，菜单引用的 CE 物品要等 CE 加载完才能通过校验）没有构造出来实测。
- A6 的 `spawnPrivate` 区域检查属于 `MenuSessions` 的 ApplicationContext（folia-runtime），本分支只补了 KDoc。
- `CraftEngineBridge.close` 里的 `shutdownNow` 改为“先等待再强停”，与 Folia 无关，没改。
- README 里的 API 章节（线程契约、`REGION_THREADING`、runFor / openAsync / track(ScheduledTask)）归 folia-build 管，本分支没改；集成时需要同步过去。
- 集成时，需要 folia-runtime 的 `ArcScheduler` 能对 1.21.1 编译（`isGlobalTickThread`）。
