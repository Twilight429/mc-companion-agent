# 构建、运行与团队协作指南

## 1. 环境与目标

| 项目 | 本项目要求 |
|---|---|
| 系统 | Windows 优先；Linux/macOS 可使用 Gradle Wrapper |
| Java | JDK 21，`JAVA_HOME` 指向 JDK 根目录，不能指向 `bin` |
| 游戏 | Minecraft Java 1.21.1 |
| 加载器 | Fabric Loader 0.18.1 或更新的兼容版本 |
| Fabric API | 本工程编译版本为 `0.116.7+1.21.1`；游戏安装适配 1.21.1 的版本 |
| 构建 | 仓库自带 Gradle 9.2.0 Wrapper，不必手装 Gradle |
| 编辑器 | IntelliJ IDEA 或 VS Code；AI coding 工具自选 |
| Python | 仅辅助导入/导出脚本需要 Python 3.10+，Java 主工程不依赖 Python 服务 |

先预留较充足的依赖缓存磁盘空间。首次联网构建会下载 Minecraft、映射和 Maven 依赖；准确耗时取决于网络，不能将本机热缓存耗时当成新成员首次构建耗时。

```powershell
git clone --branch 1.21.1 https://github.com/Twilight429/mc-companion-agent.git
cd mc-companion-agent
$env:JAVA_HOME = 'C:\实际安装位置\jdk-21'
& "$env:JAVA_HOME/bin/java.exe" -version
```

私有库需要负责人在 GitHub Settings → Collaborators 中邀请组员，组员接受邀请后再克隆。每个人使用自己的 GitHub/Git 凭据，不在群聊传令牌。上述 JDK 路径需要改为本人电脑的实际路径。

## 2. 推荐构建方式

Windows 可运行仓内辅助脚本：

```powershell
# 首次构建：生成两份 Fabric 资源，再构建游戏模组
powershell -NoProfile -ExecutionPolicy Bypass -File tools/team-build.ps1 -Mode Build

# 核心单元测试，不启动游戏、不调用付费大模型
powershell -NoProfile -ExecutionPolicy Bypass -File tools/team-build.ps1 -Mode Unit

# 独立测试玩家模型
powershell -NoProfile -ExecutionPolicy Bypass -File tools/team-build.ps1 -Mode PlayerModel

# 独立测试世界中的端到端回归
powershell -NoProfile -ExecutionPolicy Bypass -File tools/team-build.ps1 -Mode E2E
```

该执行策略只作用于这一次 PowerShell，不修改系统设置。PowerShell 7 可将 `powershell` 换成 `pwsh`。脚本按顺序运行 Gradle，任何阶段失败即停止。

不用脚本时，等价命令如下；必须分成连续调用，数据生成不能与最终打包混成一次调用：

```powershell
./gradlew.bat :numen-api:fabric:runDatagen --no-daemon
./gradlew.bat :numen:fabric:runDatagen --no-daemon
./gradlew.bat :numen:fabric:build --no-daemon

./gradlew.bat :ai:test :script:test :agent:test :numen-api:common:test :numen:common:test :ui:test :plugins:sceneadapt:test :plugins:sceneadapt:playerModelTest :plugins:ysm:test --no-daemon
./gradlew.bat :e2e:e2eTest -Pe2eServer=fabric --no-daemon
```

Linux/macOS 使用 `./gradlew`，必要时先 `chmod +x gradlew`。仅在全部依赖已缓存后才考虑 `--offline`。不要把负责人电脑的 Gradle 缓存、Java 或 Minecraft 安装路径写入源码。

当前 Gradle 配置仍会解析部分其他平台插件/公共模块依赖，所以“只构建 Fabric”不等于完全不访问 NeoForge 等 Maven 仓库。网络报错时检查对应域名、代理和防火墙；不要删除依赖、删测试或改成宽泛版本凑过编译。本地代理只在本人电脑的 Gradle 用户配置中设置，不提交到仓库。

## 3. 进游戏验证

成功产物是 `numen/fabric/build/libs/numen-fabric-1.21.1-0.1.4.jar`，不安装 `sources`、`javadoc` 或开发用 jar。版本号以 `gradle.properties` 为准，个人扩展不要自行改版号。

1. 新建隔离的 Minecraft 1.21.1 Fabric 实例，安装匹配的 Fabric API。
2. 退出客户端，把成品放入该实例的 `mods`；保留一份旧 jar，确保只有一个 Numen 主模组。内嵌 Numen API，不需要再放一份独立 API jar。
3. 使用新的测试世界，先验证正常进入游戏、原生面板与召唤。按键以游戏“控制”页为准；上游默认入口可见原生说明。
4. 在原生模型配置页设置个人供应商、模型名、兼容 API 地址和自己的 Key，测试连接并绑定同伴；不要依赖负责人电脑里的配置。
5. 单人游戏的大脑在本地集成服务器运行；多人模式的大脑与模型调用在服务器侧，个人模型 Key 通过原生配置交给所连接服务器，必须信任服主。这与语音设备在客户端处理是两回事。不要宣称多人模式 Key 永不离开客户端。
6. 先测试“跟我走”“暂停，先别建造”之类短请求，再测试采集或共同建造。观察真实物品变化与动作回执，不能仅凭聊天里的“完成了”验收。

YSM 是可选外观联动，不是完成主工程构建和测试的前置条件。运行模组与第三方 `.ysm` 模型自行取得并遵守授权；团队源码不包含用户私人模型。建筑原文件同理，放在各自实例的 `schematics`，保留作品来源与作者。

## 4. 工程导航

| 位置 | 适合修改什么 |
|---|---|
| `plugins/sceneadapt` | 确认式玩家偏好、反馈、协作信息；玩家模型测试 |
| `numen/common/src/main/resources/numen/skills/numen` | 技能说明与组合方式；已有建造、采集、互动知识 |
| `numen/common/src/main/java/com/dwinovo/numen/blueprint` | 模板、草稿、材料调查与投影的服务端接口 |
| `numen/common/src/main/java/com/dwinovo/numen/cooperation` | 真实进度观察、协作批次与互动执行 |
| `numen/common/src/client/java` | 投影与只读侧边显示 |
| `numen-api` | 唯一 Agent、任务槽、身体与权限；变更先找负责人评审 |
| `e2e/src/e2eTest` | 模拟模型→真实 HTTP/Lua/MC 服务器的回归 |
| `bench` / `bench:game` | 原生真实模型评测基础，使用前阅读 `docs/bench.md` |

`plugins/sceneadapt` 是当前 Numen 内的独立扩展模块名，**不是依赖已经归档的旧 SceneAdapt 项目**。不要把旧 Mineflayer 服务接回来，也不要另开一个脑循环。

## 5. 分支与提交

稳定集成分支为 `1.21.1`，每人从最新基线创建自己的功能分支：

```powershell
git switch 1.21.1
git pull --ff-only origin 1.21.1
git switch -c feat/member-name-food-supply
# 编码后，先检查改动，再只添加自己的文件
git diff --stat
git diff --check
git add <本人修改的具体文件>
git commit -m "feat(companion): add bounded food supply skill"
git push -u origin HEAD
```

在 GitHub 发往 `1.21.1` 的 Pull Request，写清问题、实际行为变化、如何复现、测试结果和未解决问题。由负责人集成，不直接往稳定分支推送，不强推，不替换整个 Java 类来掩盖 AI 不理解的错误。GitHub 分支保护需要负责人在网页设置，源码文件不能自动开启远端保护。

合并冲突先理解双方改动；不要用“全选我的/对方的”丢掉他人的功能。协作中优先使用原生 API 和公开插件接口。需要添加网络包、持久化字段、底层权限、模型循环时先提交接口设计。

## 6. 使用 AI coding 的方法

先让 AI 读根 `AGENTS.md`、`CONTRIBUTING.md` 和相关模块，再给它一个小任务：现有入口、预期行为、禁止越过的接口、验收条件、需要跑的测试。要求列出真实文件、改动理由、执行证据与剩余限制。

可用的起始提示：

> 阅读 docs/TEAM_START.md、TEAM_BUILD.md、TEAM_EXTENSIONS.md，以及根目录 AGENTS.md。我认领方向 X，本次只做其最小闭环。先检查已有接口，提出涉及的文件和一个可复现实验，然后实现并运行相应检查。复用唯一 Numen 大脑、原生任务与权限；禁止修改用户存档、使用管理员命令伪造任务成功或把 Key 写进代码。最后给出真实测试结果，不将模拟模型结果冒充真实 LLM 效果。

## 7. 常见故障

| 现象 | 排查 |
|---|---|
| Java 版本不对 | 查看 `JAVA_HOME/bin/java -version`，需要 JDK21；IDE Gradle JVM 也设21 |
| 拉依赖超时 | 看第一个网络错误域名；在本地配置代理，依赖齐全后可以离线构建 |
| 游戏出现翻译键/标签缺失 | 先运行两份 Fabric 数据生成，再重新打包，检查生成日志确实完成 |
| 游戏仍是旧功能 | 退出客户端、确认实例路径与 jar 哈希、删除重复安装的旧主模组 |
| 模型无响应 | 检查连通性、绑定同伴、Key、额度与服务端日志；别启动第二套模型接口 |
| Agent 说完成但没变化 | 看工具错误、实际方块与库存，不把自然语言当验收信号 |
| Git 无权访问私库 | 检查邀请是否接受、账号是否正确、凭据权限；Git 署名不等于登录认证 |

构建日志中的历史 Javadoc 引用问题与真实测试失败要区分；以最终退出码、报告和产物为准，遇到错误如实记录，不删测试以求全绿。
