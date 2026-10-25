# 来源、修改与分享范围

## 上游工程

- 上游：[Numen / 言出法随，Dwinovo 与贡献者](https://github.com/Dwinovo/minecraft-numen)。
- 本地代码起点：`daae0f14a00dfa8ab302683e93609cc862bf0798`，Minecraft 1.21.1 分支。
- 本仓库是2026-10-25当前研究主工程的**源码快照衍生版**，从独立 Git 历史开始，未复制上游 Git 对象或受限美术的历史版本。
- 代码与修改沿用 `LICENSE` 的 LGPL-3.0-only，保留上游文件头和 `licenses/GPL-3.0.txt`。不能将上游代码归为团队从零原创。

## 本地研究修改

玩家确认式偏好/反馈与协作插件；建筑索引和兼容性检查；AI原创设计、对话修改与游戏投影；批次施工与真实材料/进度；持久建筑格保护；回应式互动；原生外观选项与YSM联动调整；死亡恢复相关修正；Fabric原生协议端到端测试；团队构建和扩展指南。具体文件见源码与 `SOURCE_MANIFEST.json`。

## 美术与品牌处理

上游 `licenses/ASSETS.txt` 对部分UI纹理、原始美术、品牌和模组图标保留所有权利，不因源码 LGPL 自动变为可分发内容。本团队快照采取如下处理：

1. 不包含上游 `assets/branding` 与 `numen-api/tools/ui-textures` 中受限的图片源稿。
2. 上游许可文本中逐项列明的 pixelarticons 图标保留其 MIT 署名和许可；不根据“看起来相似”推断其他文件也为MIT。
3. `white.png`、两个chevron、`icon_mic.png`、`icon_compact.png` 以及两个模组图标改为 `tools/prepare_team_source.py` 从独立几何定义生成的占位资源，不读取或加工上游像素。这些新增占位资源由团队以CC0-1.0提供；见 `licenses/TEAM_PLACEHOLDERS.txt`。
4. 屏幕截图、演示与架构图仅按上游所列CC BY4.0范围保留，署名为“Numen (言出法随) by Dwinovo”，来源同上。涉及Minecraft内容的权利仍归相应权利人。
5. 游戏内部兼容用的模组ID/Java包名继续保留；显示名称使用“MC Companion Agent”，不冒充官方发布。占位资源只保证构建/接口可用，外观与负责人当前已安装版本可能不同。

`licenses/ASSETS.txt` 原文保留，说明的是上游原始资源，不能据此限制本仓库另行声明的独立占位资源。

## 不包含什么

不包含API Key、个人模型档案、玩家聊天/记忆、Minecraft存档、实际游戏实例、Gradle缓存、下载的运行模组、个人`.ysm`模型及用户下载的建筑作品。代码仓不是整合包。上游随源码提供的测试夹具保留原路径；团队新增测试使用自造小结构，不把用户作品复制到fixture。

`tools/import_mcblock.py` 只登记用户依法取得的本地建筑，不抓取登录内容。来源和作者署名是必要信息，不能代替再分发授权。要共享第三方建筑或模型，另行确认明确授权。

## 与原工作区的差异

除上述资源处理外，团队根README改为入门入口，建筑导入脚本默认目录改为相对的`schematics`；上传工作流仅验证Fabric，不保留官方Modrinth/CurseForge发版入口。算法、Agent循环、任务与权限逻辑来自同一份当前主工程，未为了分享删掉测试或裁掉玩家协作功能。

导出不覆盖原工程的游戏源码和已安装客户端。后续团队开发应在本仓库功能分支完成，由负责人将验收后的改进集成回日常测试版本。
