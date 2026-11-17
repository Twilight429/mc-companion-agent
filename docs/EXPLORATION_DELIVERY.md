# 视觉探索工具交付与调用链审计

> 本文保留单步 V1 的历史验收记录及集成说明；当前交付已升级为 [Explore V2 连续探索](EXPLORATION_V2.md)。最新同步验证见 [TEAM_VERIFICATION.md](TEAM_VERIFICATION.md)。

日期：2026-11-14。版本：当前 `1.21.1` 分支，Numen 0.1.4，Java 21，Fabric Loader 0.18.1；实际客户端 Fabric API 0.116.8+1.21.1。保留原版本号及已有未提交改动。

## 实际调用链与上下文

以当前源码为准：`CompanionBrains.create` 在服务端创建 `CompanionLoop`，接入 `LlmMind`、`LocalPrograms`、`OwnerTools` 和身体快照供应者；`CompanionLoop` 的 `turnRequest` 调 `AgentRequestContext.turn`。后者将 `ConvoState.snapshot()`、临时运行期消息、系统提示与完整可用工具表组成 `ModelRequest`。`LlmMind.ask` 调 `NumenLlmClient.chatStreaming`，协议适配由 `OpenAIProvider` 等负责。客户端负责 NPC 相机渲染，不运行服务端大脑循环。

| 内容 | 改造前实际来源 | 改造后 |
|---|---|---|
| System Prompt | `SystemPromptComposer`：人设/配置/默认人设、`NumenPrompts.ENTITY_PROMPT`、Skill 索引、API/模块索引、本能名册、记忆/对话/表达规则 | 保留稳定前缀，增加探索 Skill 索引 |
| 当前用户任务 | 输入进入收件箱/会话，`ConvoState.snapshot()` 随请求发出；长期目标由 `GoalPrompts` 进入同一输入链 | 保留任务与原有目标机制 |
| 历史、计划和记忆 | 会话中的用户/助手/工具消息及压缩结果；`MemoryPreamble.next` 的记忆索引，`memory`/`todo` 工具结果 | 保留必要动作账和现有压缩；不另行复制整份地图或帧预测 |
| 当前 Bot 状态 | `RuntimeState.xml()` 作为临时 `role=user` 消息：当前任务、背包、效果、骑乘、装备/插件身体片段；并非自动包含全部地形或必然包含最新 Bot 坐标 | 增加 `<exploration mode="..." position="世界x,y,z" dimension="..."/>`，每轮从身体现算 |
| 环境扫描 | Agent 主动调用 Lua 原生扫描后，返回内容进入工具回执/历史。普通请求不自动附带完整世界扫描 | 原生查询照常按需使用；不注入完整视觉语义格或实验缓存 |
| 工具定义与结果 | `parts.tools().all()`：原生 lua/skill/todo/memory 及已配置的玩家/服务器 MCP 工具；执行结果追加为 tool 消息 | 增加原生 `explore`，结果为有界 JSON 及原生移动实际账 |
| Skill | 系统前缀包含名称/描述索引；`SkillTool` 读取的正文经工具结果进入历史 | 新增 `visual_exploration`，说明触发、停止和状态转换 |
| 视觉 | `LlmMind` 对每轮有工具的、受支持视觉端点请求索取 `NpcCamera.capture`；客户端关闭视觉开关时无图。实验 JVM 属性还可启用 `ExperimentObservation` | `LlmMind` 不再自动索图。仅 `explore` 调用 `SceneCamera`；RGB 在本地模型内消费，LLM 不接收图像 |

这没有粗暴删除旧工具历史：已有动作实际账仍由原有会话/压缩机制保留。当前运行期坐标、状态优先于历史快照。没有新增“每次扫描再调用视觉/LLM”的链路。

## 工具与执行

工具名 `explore`，经 `NumenTool` / `ToolRegistry.register` 原生注册。示例参数：

```json
{"reason":"用户要求探索未知水域，需要寻找新方向","goal":"water"}
```

`goal` 支持 `any/terrain/water/wood/leaves/ore/constructed`，`finish` 表示结束探索且不拍照。Skill 实际索引名为 `visual_exploration`，可由原生 `skill` 工具加载。

一次调用只做一个探索步骤：

1. 验证身体、参数及是否已有探索/身体任务，进入 EXPLORATION。
2. `SceneCamera` 经现有 NPC 相机网络协议请求一张新图，nonce、同伴 UUID、维度与该帧眼睛位置/朝向绑定。
3. 用配置的本地 Python、ONNX Runtime 和冻结七类 ONNX 模型推理，不发送图像到 LLM。预处理匹配已有 320×180、ImageNet 均值/标准差；输出七类语义。
4. 对图像下方三分之二的左、中、右扇区进行目标类概率聚合，排除明显熔岩，按分数给出最多三个水平探索方向。投影使用实际相机 70° 垂直视野及 640:360 比例。
5. 在该帧位姿上投影约 8 格的候选水平区域。**它是近处观察航点，不是视觉识别物体的精确距离坐标。** 当前 RGB 协议没有深度，不能凭空恢复物体深度。
6. `Survey` + `Walking.spec` + `Goals.column/within` 规划候选的可站立高度，检查世界边界、已加载区域、完整原生路线和成本（上限 400，Numen 加权路径成本单位）。不固定为拍照时的高度，避免强迫挖到旧高度。
7. 第一个通过验证的视觉候选进入 `MoveToTaskRecord` / `TaskDispatch.runSync`，执行与原生 `numen.move.to` 相同的身体移动及权限机制。`execution_plan` 是可直接执行的 API 表达，LLM 无需重写；实际执行使用原生任务，不另生成自由 Lua。
8. 交回世界坐标、候选/拒绝原因、路径成本、实际移动/挖掘/放置回执，进入 REPLANNING。

原生规划允许其已有的挖掘、绕路和身体能力。未搜索到完整路径只表述当前候选未被证明可执行，不宣布沙盒区域永久不可达。途中世界变化由原生移动处理并报告失败。

## 新鲜度、状态与预算

- 相机请求超时 3 秒、固定分辨率与大小上限；原生相机回包检查该帧眼睛与当前眼睛距离不超过 0.5 格。
- 推理后和每次规划轮询再次检查位置/维度，移动超过 0.5 格即拒绝旧画面。推理上限 10 秒，采集/推理/规划阶段总计最多 15 秒。
- 每次调用总预算 45 秒。超时只取消本次持有的原生移动记录，等待原生任务交出实际账。
- 同一 Bot 的活动步骤互斥。`ToolCall.isActive` 防止用户取消后迟到的感知继续发动身体；已有原生 Stop 机制取消身体。被取消后的移动回执通过 `exploration_result` 事件保留，避免动作丢账。
- NORMAL_TASK 是默认状态。EXPLORATION 表示本步未结束。结束/受阻/无候选/过期/预算耗尽后为 REPLANNING。开始普通原生身体任务或显式 `goal=finish` 后恢复 NORMAL_TASK。
- 单步预算由工具硬性执行；跨调用探索次数由任务级 LLM 按 Skill 管理，建议最多三次，长探索由用户任务另行要求。没有额外长期自主探索循环。

## 原生接入与边界

使用原生 Tool、Skill、按需相机协议、`Survey` 路径验证、`MoveToTaskRecord`、`TaskDispatch`、权限层、身体状态贡献和世界生命周期清理。公开 SDK 增加 `SceneCamera`，原生工具调用上下文增加取消可见性；内容模块不直接依赖服务端脑循环或程序引擎内部类。

视觉推理是一次本地子进程，脚本随 mod 打包，临时脚本运行后删除。没有新增 Java/Python 第三方依赖、训练、数据集、MCP 服务或复杂 Agent 框架。原生空间缓存保持纯原生；本工具不会把视觉假设写入原生缓存。

## 客户端与配置

实际实例 `D:/MC/.minecraft/versions/SceneAdapt-1.21.1` 已从既有脚本引用、版本 JSON、mods 和配置文件核实。原生构建版本与该实例均为 Fabric Loader 0.18.1。更新只涉及 Numen jar、`config/numen/exploration.properties` 与已有 UI 中 `experimentalVisionEnabled`；不变更账号、模型凭据、存档或其它 UI 设置。

配置内容是 `python=<现有 Python 路径>`、`model=<现有冻结 ONNX 路径>`。使用 `tools/install-exploration.ps1`，显式传入客户端、解释器和模型路径。脚本验证路径、客户端未运行，备份旧 jar/配置至实例的 `.numen-backups`，写入 UTF-8 无 BOM 配置并输出 SHA256 收据。JAR 名/版本号不变，通过哈希确认已替换。

Gradle 开发客户端仍使用 `numen/fabric/runs/client`，不会把发布 jar 再放入开发 mods 导致重复加载。开发配置与实际实例分别记录。

## 清理与保护

修改前 Git 状态存于 `server_tests/explore_delivery_20261114`，相关旧文件有独立备份。已有大量未提交修改保持原样，没有 reset、清空目录或重写他人模块。

全仓 Java 引用检查确认 `ExperimentObservation` 已无调用方后，将旧实验自动注入桥归档到交付审计目录并删除源码。保留仍被按需探索使用的相机、网络、渲染和 UI 开关；保留已有其它功能及实验结果。最终原生 mod 不依赖旧的 sidecar/视觉地图/MCP 运行器。

## 验证证据

审计目录：`D:/MyNPC/server_tests/explore_delivery_20261114`。

- Fabric 构建日志：`fabric-release.log`，成功；核心模块单元测试日志 `unit-final.log`，全部通过；修正后的 common 测试再次执行通过。新增测试覆盖旧位姿/维度拒绝、取消可见性及非法调用。
- NeoForge 构建与规定的 GameTestServer 命令已尝试；本机离线缓存缺 `net.neoforged:neoforge:21.1.233`，无法完成该 Loader 的验收。日志 `build-offline.log`、`neoforge-gametest.log`。没有跳过或削弱测试来宣称通过。
- 真实 Fabric 服务端、真实 Minecraft 渲染客户端、冻结模型与本地假 LLM 已验证：普通任务不索图；LLM 原生工具调用；两次新拍摄后的移动；无矿石候选安全退出；基岩封闭环境的三个候选全部被原生规划拒绝且未移动；结束后恢复 NORMAL_TASK。
- 模型请求原文、工具结果及客户端/服务端日志保存在对应 `explore_delivery_*` 运行目录；没有把模型请求中的图像删掉再声称省上下文，实际请求本身没有图像。
- 测试不使用真实模型凭据。第一份运行因测试连接档案误用 `baseUrl`（持久化字段实际为 `base_url`）未到达本地假模型；假凭据被服务商拒绝，未产生模型用量。修正为 `provider=custom`、显式本地 `base_url` 后完整通过，失败记录保留。

本次是工程闭环验收，不宣称视觉探索优于原生搜索或能够识别七类之外的洞口等目标。


## 最终审计补充

跨模块编译曾将旧 `ENTITY_PROMPT` 内联进 `SystemPromptComposer`，导致仅更新提示词类还不够。现已改为运行期字段并由组装器显式读取，新增 `SystemPromptComposerCoreTest`，最终以实际模型请求原文核对新探索指令。失败结果统一带 `success=false`，无坐标/路径时使用 JSON null，保证 Numen 的 ToolOutcome 与调用方一致识别失败。

最终统一验收摘要在 `server_tests/explore_delivery_20261114/verification.json`，客户端构建哈希及备份在 `client-release.json`。核心测试总数以该 JSON 为准。正式使用无需启动独立 Python/MCP/相机 HTTP 服务；Java 工具按需运行本地预测子进程。多人服需要在服务端安装同一 mod 并配置解释器与模型；可渲染且已开启视觉的主人客户端负责供图。只有无渲染服务端时，工具明确返回 camera_unavailable。

构建日志仍包含仓库原有 Javadoc HTML/交叉引用诊断；按既有构建设置它们不阻止发布 jar，但本次不宣称无警告构建。NeoForge 缺失依赖的限制仍然保留。


最终结果：1,828 项核心单元测试无失败、无跳过；十项验收通过。最终运行目录 `D:/MyNPC/experiments/spatial_language_20261105/runs/explore_delivery_20261114_115728`，13 次本地测试模型请求均没有图像。最终构建、实际实例与渲染测试 mod 的 SHA256 均为 `8b76756ee8b146110326c147a5e21e333a51b5e77f202176e7e4092a02489529`。测试进程已退出，未新增付费推理。源文件清单与部署收据在 `source_manifest.json`。
