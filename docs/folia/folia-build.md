# folia-build 分支：构建、版本矩阵、文档

基于 `folia`（7580796）。范围：所有 `pom.xml`、`.github/`、`scripts/`、`README*.md`、`docs/`、`plugin.yml`；另按任务要求只改了 `StableDisplayTransform.kt` 里调用 `Quaternionf.dot` 的那一行（`render/` 属 folia-runtime）。

## 1. JOML `NoSuchMethodError: Quaternionf.dot(Quaternionf)`（env-setup §3「所有 26.3 组合都有」；audit-pack-resource §7）

- 查清：JOML 是 `provided`（`paper/pom.xml` 显式 provided，盖过 core 的传递 compile 范围；产物 jar 里 0 个 `org/joml` 类），由服务端提供。根 pom `dependencyManagement` 把它钉在 1.10.5，所以不论 `-Dpaper.version` 是多少，编译出的都是 `dot:(Lorg/joml/Quaternionf;)F`。javap 核对：1.10.5、1.10.8 是 `dot(Quaternionf)`，**1.10.9 改成 `dot(Quaternionfc)`**。各版本 paper-api 声明的 JOML：1.21.1 = 1.10.5；1.21.4 / 1.21.6 / 1.21.11 / 26.1.2 / 26.2 = 1.10.8；26.3 = 1.10.9（两个测试服 `libraries/org/joml/joml/1.10.9`）。
- 做法：`paper/.../render/StableDisplayTransform.kt:52-55` `alignHemisphere` 改成手算四元数点积（`x*x'+y*y'+z*z'+w*w'`，只读公有字段，所有版本都有）。不升 JOML：用 1.10.9 编译会链接 `dot(Quaternionfc)`，在 1.21.1–26.2 上反过来报错。不 shade / relocate：Bukkit 的 `Transformation` 直接收 `org.joml` 类型，换包名对不上。
- 新增 `scripts/check-joml-compat.py`：javap 列出编译产物（class 目录或插件 jar）里每个 `org.joml` 方法 / 字段 / 类引用，逐个在 JOML 1.10.5 / 1.10.8 / 1.10.9 里解析（含父类型继承），缺的列出引用方。修复前：`JOML 1.10.9: 1 unresolved  Method org/joml/Quaternionf.dot:(Lorg/joml/Quaternionf;)F <- StableDisplayTransforms`；修复后 90 个引用三个版本全 OK。CI 每个矩阵项都跑。
- 测试先行：新增 `paper/src/test/.../render/JomlLinkageTest.kt`（2 个测试：类文件里不能再有 `(Lorg/joml/Quaternionf;)F` 描述符；半球对齐只翻转反向的四元数）。改实现前第一个测试红，改后绿。
- Paper 上的行为：数值与 `dot` 完全相同（JOML 的 `dot` 就是这四项之和），1.21.1–26.2 不变，26.3 上 tooltip / 动画的矩阵更新恢复。
- 游戏内验证见 §5：两服 JFR 里 `Quaternionf` 相关异常 0 次（基线 WIP 每轮 130 次）。

## 2. 版本矩阵（audit-pack-resource §7、§9-1 第 3 点；audit-editor-api A8）

- 新增 `scripts/test-paper-version-matrix.sh`（上游 `Test-PaperVersionMatrix.ps1` 的 bash 版）：每项 `clean test` + JOML 检查，`-k` 失败后继续、`-o` 离线、可只跑指定版本；JDK 由 `ARCMENU_JDK21` / `ARCMENU_JDK25` 指定。26.x 用 ProtocolLib 5.5.0-SNAPSHOT。PowerShell 版同步：加 26.3、26.2 改成 build 135、每项带 ProtocolLib 版本。
- 新增 `scripts/install-protocollib-dev.sh`（参考 WIP `tinkers-test/install-protocollib-dev.sh`，通用化）：下载 GitHub `dev-build` 的 `ProtocolLib.jar`（有 gh 且已登录 / 有 `GH_TOKEN` 用 gh，否则 curl 公开下载地址），或 `-j` 指定本地 jar；`-v` 版本、`-t` tag、`-r` 仓库，`--` 后的参数传给 mvn（如 `-Dmaven.repo.local=…`）；打印 jar 内 plugin 版本和 sha256。说明：`repo.dmulloy2.net` 上也有 5.5.0-SNAPSHOT，但最后更新 10-22，早于支持 26.3 的 9cb125f，所以仍装 dev-build。两种方式都实测装进临时仓库成功。
- `.github/workflows/build.yml` 改成矩阵（`fail-fast: false`）：1.21.1、1.21.11（JDK 21，ProtocolLib 5.4.0）；26.2.build.135-stable、26.3.build.246-stable（JDK 25，先跑安装脚本装 dev-build）。每项 `clean verify` → JOML 检查 → 上传 jar。本地验证：YAML 解析、shellcheck 无告警、`bash -n` 过；没有在 GitHub 上实跑（不 push）。
- 本地实测（JDK 21 = Temurin 21.0.13，下载到 `arcmenu/dl/jdk21`；JDK 25 = zulu 25.0.4.1；日志 `/tmp/arcmenu-folia/folia-build/matrix-asis/`）：

  | Minecraft | paper-api | ProtocolLib | JDK | 结果 | 测试 |
  |---|---|---|---|---|---|
  | 1.21.1 | 1.21.1-R0.1-SNAPSHOT | 5.4.0 | 21 | **编译失败**：`ArcScheduler.kt:27` `Unresolved reference 'isGlobalTickThread'` | core 95 过后停 |
  | 1.21.4 | 1.21.4-R0.1-SNAPSHOT | 5.4.0 | 21 | 过，JOML OK | 234 过，1 跳过 |
  | 1.21.6 | 1.21.6-R0.1-SNAPSHOT | 5.4.0 | 21 | 过，JOML OK | 234 / 1 跳过 |
  | 1.21.11 | 1.21.11-R0.1-SNAPSHOT | 5.4.0 | 21 | 过，JOML OK | 234 / 1 跳过 |
  | 26.1.2 | 26.1.2.build.74-stable | 5.5.0-SNAPSHOT | 25 | 过，JOML OK | 234 / 1 跳过 |
  | 26.2 | 26.2.build.135-stable | 5.5.0-SNAPSHOT | 25 | 过，JOML OK | 234 / 1 跳过 |
  | 26.3 | 26.3.build.246-stable | 5.5.0-SNAPSHOT | 25 | 过，JOML OK | 234 / 1 跳过 |

  1.21.1 的失败来自 `folia` 分支本身（WIP 引入的 `Bukkit.isGlobalTickThread()`，paper-api 1.21.1 没有这个方法；audit-pack-resource §9-1），`ArcScheduler.kt` 归 folia-runtime，我没改。为确认除此之外 1.21.1 没有别的问题，在**临时拷贝**（`/tmp/arcmenu-folia/folia-build/src-121patch`，未提交）里按 audit 建议把它换成 `MethodHandles.publicLookup().findStatic(Bukkit, "isGlobalTickThread", …)` 反射调用后重跑：1.21.1 过，234 测试过 / 1 跳过，JOML OK。**在 folia-runtime 修掉这一行之前，CI 的 1.21.1 项和缺省 `mvn clean verify` 都是红的。**
- 注：本地 `~/.m2` 的 ProtocolLib 5.5.0-SNAPSHOT 仍是 10-26 的 6720a70（没覆盖，env-setup 有意保留）；26.x 的编译只依赖 ProtocolLib 的公开 API，与 9cb125f 无差别。

## 3. 文档

- `docs/folia/README.md`：Folia 移植总览——支持的平台 / 版本与验证程度、Java、ProtocolLib 要求、JOML 约束、线程模型概要（取自 audit-runtime §3，另两路完成后补细节）、API 线程契约草案（audit-editor-api §3.3）、不支持热重载 / PlugMan、已知限制、与上游的差异、构建命令。
- `README.md` / `README_zh_CN.md`：Paper 徽章 `1.21.1–26.2` → `1.21.1–26.3`（矩阵 7 个版本都能编译和通过测试，前提是 1.21.1 那一行修好），新增 `Folia 26.3` 徽章链到总览；「从源码构建」里的版本范围与矩阵说明补上 26.3；新增「Folia」一节指向 `docs/folia/README.md`。
- `.gitignore`：上游忽略整个 `/docs/`（本地笔记），改成 `/docs/*` + `!/docs/folia/`，否则 `docs/folia/*.md` 进不了提交。**另两路写 `docs/folia/<分支>.md` 时如果基于未合并本分支的 `folia`，需要 `git add -f`。**

## 4. `plugin.yml`（`paper/src/main/resources/plugin.yml`）

- `folia-supported: true` 已在（`folia` 分支带来的），不改。
- `api-version: '1.21'` 不改：1.21.1–26.3 都接受（实测 Paper 26.3 build 246 与 Shiroha 26.3 都正常加载；矩阵各 API 都能编译）；写成 26.x 会让 1.21 服务端拒绝加载。

## 5. 游戏内验证（本分支 26.3 API 编译的 jar，含 JOML 修复；1.21.1 基线编译不过所以用 26.3 产物）

- jar：`/tmp/arcmenu-folia/folia-build/ArcMenu-folia-build-api263.jar`。脚本 `/tmp/arcmenu-folia/folia-build/ingame.sh`：持 `/tmp/arcmenu-server.lock` 完成「放 jar → 起服 → 挂 JFR → 跑两个场景 → 停服 → 收日志 → 把 jar 和数据目录移出 `plugins/`」，两服各一遍。为此给 `arcmenu/run-scenario.sh` 加了可选的 `ARC_LOCK_HELD=1`（调用方已持锁时不再加锁，缺省行为不变）。
- 新场景 `arcmenu/scenarios/folia-build-arc-smoke.txt`：`/plugins` 有 ArcMenu → `status` → touch 模式开 example，悬停 card / model 区（tooltip 矩阵更新）→ 关 → 开 m5-animation（动画矩阵更新）→ 关 → `performance` → 16 格内无残留 text_display。
- 结果（全部 `exit=0`、所有 expect 过）：

  | 服 | folia-build-arc-smoke | arc-menus | 日志里 NoSuchMethodError / Quaternionf | JFR：带 ArcMenu 栈帧的 NoSuchMethodError | 线程检查 / UOE / ISE |
  |---|---|---|---|---|---|
  | Folia（Shiroha 26.3） | 过 `shots/1110-113524-folia-folia-build-arc-smoke/` | 过 `shots/1110-113636-folia-arc-menus/` | 0 / 0 | 0（基线 WIP 130 次 `Quaternionf.dot`） | 0 |
  | Paper 26.3 build 246 | 过 `shots/1110-113809-paper-folia-build-arc-smoke/` | 过 `shots/1110-113913-paper-arc-menus/` | 0 / 0 | 0（基线 WIP 130 次） | 0 |

  JFR 里剩下的 NoSuchMethodError（Folia 150、Paper 96）全是 JVM 内部 `MemberName.resolveOrNull` 对 `DirectMethodHandle$Holder` 的探测（区块系统、record `equals` 引导、ProtocolLib），基线里同样有，与 ArcMenu 无关。`performance` 显示 matrix writes=207 / 574，说明矩阵更新路径确实跑过。截图（亲自看过）：example 菜单与旋转 card、m5 动画菜单在两服都正常显示。
  证据：`/tmp/arcmenu-folia/folia-build/ingame-r1/`（`results.txt`、`*-full.log`、`*-exc.jfr`、`*-exc.txt`、`*-exc-summary.txt`、`*-plugin-data/`），统计脚本 `/tmp/arcmenu-folia/folia-build/nsme.py`。

## 没做 / 后续

- `ArcScheduler.kt:27` 的 1.21.1 编译错误留给 folia-runtime（建议写法见 §2）；合并后 CI 1.21.1 项才会绿。
- CI 没有在 GitHub 上实跑。
- 游戏内只测了 26.3（Paper 与 Shiroha）；1.21.x / 26.1–26.2 服务端没有实机。
- `docs/folia/README.md` 的线程模型细节、已知限制要在另两路完成后更新。
- README「从源码构建」里上游写的 `paper/target/ArcMenu-3.0.0.jar` 已与版本号不符（上游原文），没动。
