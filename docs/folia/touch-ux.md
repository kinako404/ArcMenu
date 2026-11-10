# folia-touch-ux：touch 模式体验修复

分支 `folia-touch-ux`（基于 `folia-touch-input-end` ed8beda）。起因是匠魂冶炼炉 demo（TASK-1110）第二版只用 touch 模式：潜行时准星在画布上跳、转头后指针偶尔不更新、左右键要稳定送达且不能误伤世界。`onActivate` 的语义不变（双击仍由应用自己判断）。

## ① 界面跟着眼睛高度移动

**问题**：touch 模式的运行平面离眼睛只有 0.65 格（`offset.yml` `touch.distance`），潜行时眼高 1.62 → 1.27，平面不动，同一视线在画布上的落点一下移 0.35 格 × 像素密度（自带 `example` 197 px / 格是 69 px，demo 是 113 px），蹲下点东西要先重新瞄。

**做法**（`MenuSessions.followEyeHeight`，touch 模式的运行菜单与应用，每 tick）：
- 会话 / 应用记下平面对应的眼高 `planeEyeHeight`（打开、重锚时取 `player.eyeHeight`；沿用上一个菜单的平面时随 `Placement.eyeHeight` 带过去）。
- 发现 `player.eyeHeight` 变了就把整块屏幕竖直平移同样的差：`MenuPlane.translated`（原点与 `viewerEye`）、`MenuView.translate`（全部展示实体、锚点、分层深度用的眼睛位置）、捕获用的 Interaction、应用 `spawnPrivate` / `track` 的全部实体、`routePlacement`。准星的画布坐标因此不变；指针计算一直用 `session.plane` / `runtime.plane`，即移动后的平面。
- 眼高按姿态取，所以潜行、站起、游泳 / 爬行（0.4）、鞘翅滑翔、`scale` 属性都覆盖；走动、跳跃不跟（touch 菜单本来就固定在世界里）。mouse 模式是固定摄像机，不需要。

**怎么移**：Folia 的 `Entity.teleport` 直接抛 `UnsupportedOperationException`，`teleportAsync` 会把实体移出再加回（客户端看到的是消失再出现，没有插值）。`platform/ArcEntityMover` 反射 `getHandle().setPos(x, y, z)` 并置 `hasImpulse`（Interaction 的追踪间隔是 3 tick，置位后下一 tick 就发），由实体追踪器发普通的移动包；Paper 上走同一条路。

**平滑**：展示实体的 `teleportDuration` = 2（`SURFACE_FOLLOW_TICKS` = `ArcMenuSurfaceMoveEvent.DEFAULT_DURATION_TICKS`）。
- 26.3 客户端 `Camera.tick`：`eyeHeight += (目标 - eyeHeight) * 0.5`，每 tick 走剩余的一半（0.5 / 0.75 / 0.875 …），渲染时在两 tick 间线性插值。
- `ServerEntity.sendChanges` 先发移动包、后发实体元数据（字节码确认），同一 tick 改 duration 只对下一次移动生效。所以 ArcMenu 自己的实体在生成时就设好 duration；应用实体 duration 不同的，当 tick 改成 2、下一 tick 再移、移完（2 + 2 tick）后若还是 2 就恢复原值。应用想零延迟就在生成展示实体时 `setTeleportDuration(ArcMenuSurfaceMoveEvent.DEFAULT_DURATION_TICKS)`。
- 实测（测试客户端 `eyetrack`，本机，镜头眼高 / 最近的菜单展示实体 Y，相对按键前，单位格）：

  | tick | 1 | 2 | 3 | 4 | 5 | 6 |
  |---|---|---|---|---|---|---|
  | 镜头 | 0 | 0.175 | 0.262 | 0.306 | 0.328 | 0.339 |
  | 界面，duration 2（Folia；Paper 4 次里 1 次晚 1 tick） | 0 | 0 | 0.175 | 0.350 | 0.350 | 0.350 |
  | 界面，duration 3（Paper） | 0 | 0 | 0 / 0.117 | 0.117 / 0.233 | 0.233 / 0.350 | 0.350 |

  服务端要等收到输入包、玩家 tick 换了姿态，菜单 tick 才看得到，移动包到客户端时镜头已经走了 1–2 tick。duration 2 第一 tick 差 0.175 格（约 34 GUI px），之后 ≤ 0.044 格；duration 3 一直追不上，所以取 2。公网延迟会让起步再晚一些。

**API**：`ArcMenuApplicationSession.onSurfaceMoved(ArcMenuSurfaceMoveEvent)`（`surface`、`offset()`、`durationTicks`）。抽象类新增带缺省实现的 open 方法，旧应用二进制兼容。和 `onSurfaceChanged(initial = false)`（重锚：实体已被清掉，要重建）不同，跟随时什么都没重建，ArcMenu 已经把应用跟踪的实体都挪好了；应用只需更新缓存的世界坐标（例如之后 `spawnPrivate` 新实体用的锚点）。`onSurfaceChanged` 不会因跟随触发。

## ② 转头后指针偶尔不更新

**复现**：`../arcmenu/scenarios/touch-ux-look-repro.txt`（第一版报告里的动作：蹲下点、站起、转头），Folia 与 Paper 每次都在第一轮复现；`touch-ux-look-repro2.txt` 对照：**不开菜单也一样**，服务端玩家的 yaw / pitch 停在旧值。

**根因**（临时包日志确认 LOOK 包已到、未被取消，2 tick 后服务端 `getYRot()` 仍是旧值）：CraftBukkit 的 `PlayerMoveEvent` 路径。服务端任何传送（`/tp`、插件传送）把 `justTeleported` 置真，之后只在“发 PlayerMoveEvent”的分支里清掉；那个分支先把玩家退回上一个位置 / 朝向，发事件，再检查 `!from.equals(当前位置) && justTeleported` 就直接 `return`，不再应用这个包。`from` 是上次发事件时记下的位置 / 朝向，而小于 10° 的转头不发事件、但会被应用，于是“传送 → 小转 → 一次超过 10° 的转”这一包被整包丢掉。客户端只在朝向变化时发包，不会重发，服务端朝向就一直旧到玩家下次再转。真人玩家鼠标总在动，下一包就纠正；测试客户端一步转到位就会卡住。

**修**：`PointerController` 记下客户端在 LOOK / POSITION_LOOK、以及 26.3 接受传送包里带的转角（`ViewRotation`）；`TouchPointer` 用它算视线（`viewDirection`，与 `Location.getDirection` 同公式），touch 平面的朝向也用它；没有记录时回落服务端值。服务端拒绝移动时会发传送，客户端接受（26.3 的接受包带转角，旧版随后补一个 POSITION_LOOK）后记录随之更新。服务端玩家的朝向不改、不发包。玩家退出时清掉。

## ③ 左右键：送达且不作用于世界

**为什么指着界面时不会误伤**：每个菜单有一个只对本人可见的 Interaction（宽、高 = 画布，缺省画布下把眼睛也包在里面），客户端准星打在它上面：
- 左键：26.3 客户端发 `ServerboundAttackPacket`（实体）+ `ServerboundPunchPacket`（26.3 起没有挥手包），ArcMenu 吞掉攻击包——不挖方块、不打后面的生物；按住左键时 `continueAttack` 只对方块生效，拿镐子也不会开始挖。
- 右键：`ServerboundInteractPacket`，Interaction 在客户端返回 SUCCESS，不会再落到“使用手上物品”，不吃东西、不拉弓、不放方块；ArcMenu 吞掉交互 / 使用包。

**改动**：
- `onInteract`：菜单开着时副手的 `PlayerInteractEvent` 也取消（以前只管主手。准星没打在捕获实体上——指着画布外的方块——时主手 PASS 后客户端会用副手放方块）。
- 26.3 上 Paper 把 punch 包的射线没打中任何东西时变成 `LEFT_CLICK_AIR`，以前靠 75 ms 去抖压掉这次重复激活；现在包层接管输入时不再从这个事件激活。
- 去重：以前所有激活共用 75 ms 去抖（左右键一起算，连点超过约 13 次 / 秒就丢）。现在包层按客户端 tick 去重（`ClickPairing`）：一个客户端 tick 内每个键最多一次激活；挥手包只在本 tick 没有别的点击包时算左键（旧协议攻击 / 使用后都会挥手）；方块使用落空后的物品使用不重复算。tick 边界用 26.x 的 `ServerboundClientTickEndPacket`，更早的协议用 30 ms 窗口。**连点最小间隔 = 1 个客户端 tick（50 ms），即每键最多 20 次 / 秒，没有额外抖动去重**；同一 tick 里按了两下（超过 20 次 / 秒）合并成一次。只有 Bukkit 事件兜底（指着方块的左键）仍走 75 ms 去抖。
- 编辑器插件通道的点击同样不再去抖（一次点击一条消息）。

**客户端自身的限制**：拿着有 `minimum_attack_charge` 的物品（矛）蓄力不足时左键不发任何包；生存模式左键打空后 10 tick 内的左键客户端不发（指着界面时打在捕获实体上，不会打空）。眼睛不在捕获盒里时（画布很窄、`offset` 把画布挪开、走开了）指向画布外的点击会打到世界：方块的挖 / 用被取消，攻击任何实体的包仍被吞。

## 验证

- 单元测试 `mvn -o verify`（JDK 21，缺省基线 Paper API 1.21.1）：267 个全过（原 258 + 新 9：`MenuGeometryTest` 平移、`TouchInputTest` 5 个（视线公式、指针用上报转角、tick 内去重、跨 tick 不合并、无 tick-end 时的窗口）、`EyeFollowTest` 2 个、`ArcMenuSurfaceTest` 新事件）。
- 游戏内（`../arcmenu/scenarios/`，Folia 25611 与 Paper 25612 两服）：
  - `touch-ux-verify`（菜单 `touch-ux-test.yml`）：传送后先小转再转 22°，指针 x=-46.62 y=-24.04 正确；潜行、站起后同一视角指针不变；第一版的复现动作后指针更新到 x=-74.41；爬行（头顶放方块，眼高 0.4）y 不变；拿钻石镐指着界面后面的石头创造 / 生存连点 8 次石头还在、指着后面的盔甲架左键它还在；右键雪球没扔出、弓没射箭、石头没放、副手火把还在；左键 10 次（含 1 tick 间隔连点 5 次）全部送达、右键 11、潜行左 2、潜行右 3，数字与动作一一对应。两服都过。截图 `shots/*-touch-ux-verify/`（站立 / 潜行 / 爬行同一视角，界面在画面里的位置不变）。
  - `touch-ux-eyetrack`：上表数据。
  - `run-regression.sh`：见 `../arcmenu/shots/regress-touchux-568c6b7/summary.txt`。

## 遗留

- 跟随起步比镜头晚 1–2 tick（服务端要先看到姿态），公网再加单程延迟；不装客户端 mod 做不到更早。
- 应用私有实体如果自己设了别的 `teleportDuration`，跟随时晚 1 tick 才移。
- 版本号仍是 `3.0.1-folia.1`。
