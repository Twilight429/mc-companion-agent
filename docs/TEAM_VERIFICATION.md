# 团队源码快照验证

## 2026-11-17：最终 Explore V2 同步

本次验证在团队仓库独立检出目录完成，保持原研究工程的未提交修改和已安装客户端不变。先核对最终交付清单，再同步源码；不是仅按文件修改时间判断版本。

| 检查 | 本次结果 |
|---|---|
| 最终 V2 源码版本 | 6 个关键文件 SHA256 与 2026-11-14 最终交付清单全部一致 |
| 当前工程同步 | 67 个变更的运行、构建及测试源码与主工程逐项一致 |
| 正式客户端版本 | 已安装 JAR SHA256 与最终验收件一致：`71dfa059db6653ecf6411b649938eb0c49cbd1d244105ece055e0410b0f4be4b` |
| Fabric API / 内容资源生成 | 成功 |
| Fabric / NeoForge 构建 | 均成功，保留原有 Javadoc 与弃用诊断 |
| 核心单元测试 | 1,838 项通过，0 失败、0 错误、0 跳过 |
| 核心 + UI + 插件单元测试 | 共 2,070 项通过，0 失败、0 错误、0 跳过；包含上述核心测试 |
| 独立玩家模型测试 | 17 项通过，已计入插件测试，不重复累加 |
| Python 视觉观察辅助测试 | 2 项通过 |
| NeoForge 全套 GameTest | 657 项运行结束，20 项必需用例失败；游戏进程退出码 20，Gradle 退出码 1 |
| 本地文档相对链接 | 30 个链接目标均存在 |
| Fabric 真实协议端到端 | 26 项执行，24 项通过、2 项失败、0 错误、0 跳过 |
| 上传候选审计 | 2,112 个文件，未命中密钥或运行数据检查规则；模式扫描不是绝对安全证明 |

本次按顺序使用 Java 21、已下载的本机依赖缓存以及 `--offline --no-daemon --console=plain`，执行：

```powershell
.\gradlew.bat :numen-api:fabric:runDatagen :numen:fabric:runDatagen :numen:fabric:build :numen:neoforge:build --offline --no-daemon --console=plain
.\gradlew.bat :ai:test :script:test :agent:test :numen-api:common:test :numen:common:test :ui:test :plugins:sceneadapt:test :plugins:sceneadapt:playerModelTest :plugins:ysm:test --offline --no-daemon --console=plain
```

已有构建辅助入口为 `tools/team-build.ps1`；新电脑首次下载依赖时去掉 `--offline`。Python 辅助测试用 `python tools/test_npc_surface_observer.py`，需要从脚本目录解析辅助模块。

团队 Fabric 成品 SHA256：`cec227afba47bba831a45a7a631cb26115875a1927fc4e13fd55ab9bff125746`。团队版使用独立占位图标与显示名称，故不要求与正式客户端原安装件的二进制哈希相同；本次没有替换该客户端。源码仓库不提交构建 JAR、模型、游戏世界、个人配置和实验数据。

本次复核没有重跑视觉渲染演示或付费 LLM。已有自然地形演示的一次调用、3 个航点、103.03 格来自 2026-11-14 的真实记录；工具路由使用本地假 LLM，不能据此宣称真实 LLM 自主探索能力已经评估。完整接口与历史对比见 [Explore V2](EXPLORATION_V2.md)。

上传前发现旧 `.gitattributes` 把 DOCX 当文本，导致原 Git 中手册少一个字节、ZIP 校验失败。本次追加 `*.docx binary` 并重新收录完整本地手册；本地及待提交版本均通过 ZIP 完整性校验。全部 2,111 个清单条目的待提交 Git 内容与 SHA256 一致。新增导入的 `NPC_WORLD_SIMULATION_PLAN.md` 有两处原有 Markdown 双空格硬换行，保留原文；没有其它差异空白诊断。

### 本次 Fabric 端到端结果

第一次离线执行在解析 MCProtocolLib 依赖时停止，尚未运行用例。补齐依赖后执行 `.\gradlew.bat :e2e:e2eTest -Pe2eServer=fabric --no-daemon --console=plain`，隔离服务正常启动并结束；26 项中 24 项通过、2 项失败：

- `NativeClientTest.npc_camera_reaches_the_model_only_from_the_owner_and_is_request_scoped`：普通聊天任务后未等到相机请求。该测试仍要求普通推理自动拍照，与当前 Explore 按需采图、普通推理不索图的设计冲突，测试尚未同步；不把该失败认作视觉探索真实渲染已经失败。
- `RespawnTest.summoning_during_the_death_window_cannot_bypass_the_native_respawn`：死亡窗口内重召后玩家列表仍含同伴，断言失败。原因未在本次上传任务中进一步修复或归因。

因此不宣称当前 Fabric 端到端全通过。GitHub Actions 包含相同测试，应以实际云端结果为准；本次保留测试和当前交付逻辑，没有为了上传删掉测试或放宽断言。测试使用本地假模型，没有付费 LLM 调用。Paper 测试未运行。

### 本次 NeoForge 失败记录

以下 20 项均已出现在 2026-11-14 的 21 项失败清单中；本次没有出现新的失败用例名，梯子到达用例本次未复现失败。失败数量变化不等于已经修复。涉及建造与方块实体、资源标签、命令/权限通知、搭高移动等；没有删改、跳过或放宽测试。NeoForge 仅构建通过，完整游戏验收仍未通过。

```text
numen.buildgametests.blueprint_block_entity_contents_survive
numen.buildgametests.blueprint_handiwork_is_not_free
numen.buildgametests.build_japanese_cottage
numen.buildgametests.safe_block_entity_data_is_a_datapack_tag
numen.buildgametests.skill_docs_name_real_blocks
numen.clientlessgametests.a_player_without_our_client_is_told_in_the_language_his_client_reports
numen.clientlessgametests.a_withdrawn_request_says_why_to_an_owner_without_our_client
numen.clientlessgametests.an_owner_without_our_client_sees_what_she_is_doing_on_the_action_bar
numen.commandgametests.a_kick_removes_her_body_and_the_owner_can_summon_her_back
numen.modegametests.creative_pillar_out_empty_handed
numen.movementgametests.follow_builds_up_to_keep_up
numen.movementgametests.goto_up_a_tower_pillars_up
numen.movementgametests.the_pillar_she_built_is_hers_to_take_down
numen.permissiongametests.a_placed_deny_row_guards_what_players_placed
numen.permissiongametests.a_rule_for_one_companion_leaves_the_others_alone
numen.permissiongametests.an_owners_ask_row_beats_the_factory_allow
numen.permissiongametests.consent_command_answers_like_the_card
numen.permissiongametests.she_tightens_at_once_and_loosening_goes_through_a_card
numen.permissiongametests.the_rule_list_shows_how_often_each_row_fired
numen.plugingametests.a_plugins_body_state_and_event_reach_her
```

## 2026-10-25：初始团队快照历史记录

日期：2026-10-25。验证目录为独立团队快照，不直接沿用原工作区的测试报告。

| 检查 | 结果 |
|---|---|
| Fabric API 资源生成 | 成功，语言文件生成完成 |
| Fabric 内容资源生成 | 成功，物品与方块标签生成完成 |
| Fabric 1.21.1 构建 | 成功，包含独立占位图标与内嵌 API |
| 核心/插件单元测试 | 2037项通过，0失败、0跳过 |
| 独立玩家模型测试 | 17项通过；它们也包含在插件完整测试内，不重复计入2037 |
| 隔离 Fabric 世界端到端 | 24项通过，0失败、0跳过 |
| 分享候选文件扫描 | 未发现规则可识别的密钥串或待提交运行数据；模式扫描不是绝对安全证明 |
| 团队 Markdown 相对链接 | 检查通过 |

## 实际执行

按 `tools/team-build.ps1 -Mode Build`、`-Mode Unit`、`-Mode E2E` 顺序执行，每次Gradle使用 `--no-daemon`。构建日志中有上游既有Javadoc引用问题，但最终构建退出成功，真实测试报告无失败；未因此删改或跳过测试。

验证复用了原电脑已经下载的Gradle/Minecraft依赖缓存，未使用原工程的源文件、游戏配置、私人模型或存档作为构建输入。本次不是全新电脑的冷缓存下载验证；成员首次下载仍受网络影响。

本地成品：`numen/fabric/build/libs/numen-fabric-1.21.1-0.1.4.jar`，SHA256：`0adbba71a213b707cec2e11ad4ee22630b28cf2c3aece8709291c758035ae482`。本仓库提交源码，不包含该构建产物。

## 结论边界

- 端到端测试使用本地模拟模型，验证真实协议、Lua与游戏动作；不是DeepSeek长时自主陪玩的效果证明。
- 占位图标、侧边浮窗和投影的实际画面尚未在此快照上人工验收；原安装客户端未被替换。
- 未运行其他加载器和全量原生GameTest；不宣称NeoForge/Paper或全部游戏机制已验证。
- 初始源码快照的 `git diff --check` 会报告上游Lua/许可证等既有空白格式，保留原样；本次新增指南/模板单独检查，不批量重排第三方源码。
- GitHub云端CI已提供配置；其首次远端运行结果需在Actions查看，不能把本地结果写成云端通过。
