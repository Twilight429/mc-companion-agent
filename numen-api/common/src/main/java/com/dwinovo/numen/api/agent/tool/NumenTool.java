package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.provider.IToolSpec;

/**
 * 模型工具表里的一个工具。LLM 侧的描述面(name/description/parameterSchema)继承自连接层的 {@link IToolSpec},执行面只有
 * {@link #invoke}。普通身体动作经脚本({@code ScriptTool})内的 API;内容包也可登记有界探索工具并复用同一原生身体任务。另三个只管大脑
 * 自己的事:装技能({@code SkillTool})、记计划({@code TodoTool})与札记({@code MemoryTool})。再有就是主人接进来的外部 MCP 工具,各自带着协议去调外部服务。
 */
public interface NumenTool extends IToolSpec {

    /**
     * Run this tool for one call — Numen API's ONLY entry point. 当场完成、去异步、发自己的包都由工具自便,最后经
     * {@link ToolCall} 报结果。
     */
    void invoke(ToolCall call);
}
