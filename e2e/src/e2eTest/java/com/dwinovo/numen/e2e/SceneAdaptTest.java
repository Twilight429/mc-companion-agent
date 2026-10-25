package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;

/** 真服务端、原生身体与原生模型循环上的玩家记忆扩展，不连接旧 Mineflayer 服务。 */
class SceneAdaptTest extends Scenario {
    @Test void confirmed_player_context_composes_with_native_scan_and_movement_without_breaking_the_target() throws Exception {
        Bot owner = join("e2e_coscout", "en_us");
        summon(owner, "Luna");
        int mark = owner.mark();
        owner.command("numen sceneadapt memory set scouting observe without gathering");
        owner.await(mark, m -> m.text().contains("已保存：scouting"), WAIT, "保存观察优先偏好");
        assertTrue(console("tp Luna 100.5 -60 100.5").contains("Luna"));
        assertTrue(console("setblock 105 -60 100 minecraft:gold_block").contains("Changed"));
        MODEL.whenHeard("scout the gold without gathering", lua("local c = sceneadapt.player.context(); "
                + "assert(c.preferences[1].value == \"observe without gathering\"); "
                + "local found = numen.scan.blocks(\"gold_block\", {radius = 8}); "
                + "assert(#found == 1 and found[1].count == 1); "
                + "print(\"SCENEADAPT_NATIVE_SCAN:\" .. found[1].nearest.pos.x); "
                + "local walked = numen.move.to(found[1].nearest, 2); "
                + "assert(walked.dug == 0 and walked.placed == 0); "
                + "print(\"SCENEADAPT_NATIVE_WALK:\" .. walked.dug .. \":\" .. walked.placed)"),
                say("Scouted the gold without gathering."));
        mark = owner.mark();
        owner.command("msg Luna scout the gold without gathering");
        owner.await(mark, m -> m.text().contains("Scouted the gold without gathering."), WAIT, "真实扫描与移动的回话");
        assertModelSawToolResult("SCENEADAPT_NATIVE_SCAN:105", "SCENEADAPT_NATIVE_WALK:0:0");
        assertTrue(MODEL.asked().stream().anyMatch(a -> a.body().contains("player_cooperation")),
                "bundled cooperation skill was not discoverable by the native brain");
        // execute 的条件答复回 RCON；say 只广播给玩家，不能把它的空 RCON 回包当成条件失败。
        String position = console("execute if entity @a[name=Luna,x=105,y=-60,z=100,distance=..3.5]");
        assertTrue(position.contains("Test passed"), "native body did not actually reach the scanned target: " + position);
        String target = console("execute if block 105 -60 100 minecraft:gold_block");
        assertTrue(target.contains("Test passed"), "observing unexpectedly destroyed the target: " + target);
    }

    @Test void owner_approval_is_required_and_a_different_player_cannot_approve_the_proposal() throws Exception {
        Bot owner = join("e2e_coowner", "en_us");
        Bot stranger = join("e2e_costranger", "en_us");
        summon(owner, "Cora");
        // 测试服与玩家均为新建，资料天然为空；不额外发送会耗尽原版聊天配额的清空指令。
        MODEL.whenHeard("remember my mining preference",
                lua("sceneadapt.player.propose_memory(\"mining_style\", \"Player finds ore first\", \"Owner said: I want to find ore first\")"),
                say("The memory proposal is awaiting your approval."));
        int mark = owner.mark();
        owner.command("msg Cora remember my mining preference");
        Bot.Message card = owner.await(mark,
                m -> Text.clickCommands(m.content()).stream().anyMatch(c -> c.startsWith("/numen sceneadapt proposal accept ")),
                WAIT, "记忆确认卡片");
        List<String> buttons = Text.clickCommands(card.content());
        assertEquals(2, buttons.size());
        String approve = buttons.stream().filter(c -> c.startsWith("/numen sceneadapt proposal accept ")).findFirst().orElseThrow();

        int strangerMark = stranger.mark();
        stranger.click(approve);
        stranger.await(strangerMark, m -> m.text().contains("提议不存在"), WAIT, "其他玩家不能代为确认");
        mark = owner.mark();
        owner.command("numen sceneadapt");
        owner.await(mark, m -> m.text().contains("0 条确认记忆") && m.text().contains("1 条待确认提议"), WAIT, "提议尚未成为记忆");

        mark = owner.mark();
        owner.click(approve);
        owner.await(mark, m -> m.text().contains("已确认玩家偏好"), WAIT, "主人确认记忆");
        owner.command("numen sceneadapt");
        owner.await(mark, m -> m.text().contains("mining_style = Player finds ore first") && m.text().contains("owner_confirmed"),
                WAIT, "已确认且可撤销的记忆");

        MODEL.whenHeard("inspect cooperation context", lua("local c = sceneadapt.player.context(); "
                        + "print(\"SCENEADAPT_CONFIRMED:\" .. c.preferences[1].key .. \":\" .. c.preferences[1].value)"),
                say("Cooperation context inspected."));
        mark = owner.mark();
        owner.command("msg Cora inspect cooperation context");
        owner.await(mark, m -> m.text().contains("Cooperation context inspected."), WAIT, "原生大脑调用扩展接口");
        assertTrue(MODEL.asked().stream().anyMatch(a -> a.body().contains("Player finds ore first")
                && a.body().contains("sceneadapt_player_data")), "confirmed preference did not reach the native brain");
        assertModelSawToolResult("SCENEADAPT_CONFIRMED:mining_style:Player finds ore first");

        mark = owner.mark();
        owner.command("numen sceneadapt memory forget mining_style");
        owner.await(mark, m -> m.text().contains("已删除该偏好"), WAIT, "玩家撤销偏好");
        owner.command("numen sceneadapt");
        owner.await(mark, m -> m.text().contains("0 条确认记忆") && m.text().contains("0 条待确认提议"), WAIT, "撤销后的资料");
        MODEL.whenHeard("inspect revoked memory", lua("local c = sceneadapt.player.context(); "
                + "print(\"SCENEADAPT_REVOKED:\" .. #c.preferences)"), say("Revocation inspected."));
        mark = owner.mark();
        owner.command("msg Cora inspect revoked memory");
        owner.await(mark, m -> m.text().contains("Revocation inspected."), WAIT, "撤销后实际规划输入");
        assertModelSawToolResult("SCENEADAPT_REVOKED:0");
    }

    @Test void shared_division_and_feedback_reach_the_same_native_brain() throws Exception {
        Bot owner = join("e2e_coworker", "en_us");
        summon(owner, "Nora");
        MODEL.whenHeard("divide shelter work",
                lua("sceneadapt.player.propose_task(\"Shelter together\", \"Build walls\", \"Bring planks only\")"),
                say("Division proposed, not yet active."));
        int mark = owner.mark();
        owner.command("msg Nora divide shelter work");
        Bot.Message card = owner.await(mark, m -> m.text().contains("Shelter together")
                && !Text.clickCommands(m.content()).isEmpty(), WAIT, "协作分工确认卡片");
        String accept = Text.clickCommands(card.content()).stream()
                .filter(c -> c.startsWith("/numen sceneadapt proposal accept ")).findFirst().orElseThrow();
        mark = owner.mark();
        owner.click(accept);
        owner.await(mark, m -> m.text().contains("已确认协作分工"), WAIT, "玩家确认协作分工");

        mark = owner.mark();
        owner.command("numen sceneadapt feedback intrusive");
        owner.await(mark, m -> m.text().contains("0.400"), WAIT, "显式评价统计");
        MODEL.whenHeard("inspect my division", lua("local c = sceneadapt.player.context(); "
                + "print(\"SCENEADAPT_DIVISION:\" .. c.division.goal .. \":\" .. c.division.npc_role); "
                + "print(\"SCENEADAPT_INTRUSIVE:\" .. c.feedback.intrusive)"), say("Division inspected."));
        mark = owner.mark();
        owner.command("msg Nora inspect my division");
        owner.await(mark, m -> m.text().contains("Division inspected."), WAIT, "原生循环读取分工与评价");
        assertModelSawToolResult("SCENEADAPT_DIVISION:Shelter together:Bring planks only", "SCENEADAPT_INTRUSIVE:1");

        mark = owner.mark();
        owner.command("numen sceneadapt task Nora finish");
        owner.await(mark, m -> m.text().contains("玩家已结束该分工"), WAIT, "玩家结束协作分工");
        MODEL.whenHeard("inspect ended division", lua("local c = sceneadapt.player.context(); "
                + "print(\"SCENEADAPT_DIVISION_ENDED:\" .. tostring(c.division == nil))"), say("Ended division inspected."));
        mark = owner.mark();
        owner.command("msg Nora inspect ended division");
        owner.await(mark, m -> m.text().contains("Ended division inspected."), WAIT, "撤销分工后规划上下文");
        assertModelSawToolResult("SCENEADAPT_DIVISION_ENDED:true");
    }

    private static void assertModelSawToolResult(String... expected) {
        List<String> actual = MODEL.asked().stream().flatMap(a -> a.toolResults().stream()).distinct().toList();
        assertTrue(actual.stream().anyMatch(text -> java.util.Arrays.stream(expected).allMatch(text::contains)),
                "missing actual tool receipt " + java.util.Arrays.toString(expected) + "; actual tool messages: " + actual);
    }

    @Test void normal_chat_uses_confirmed_preferences_and_clearing_revokes_the_context() throws Exception {
        Bot owner = join("e2e_conatural", "en_us");
        summon(owner, "Eve");
        int mark = owner.mark();
        owner.command("numen chat Eve");
        owner.await(mark, m -> m.text().contains("Your chat now goes to Eve"), WAIT, "选择原生会话");
        mark = owner.mark();
        owner.command("numen sceneadapt memory set teamwork ask before taking over");
        owner.await(mark, m -> m.text().contains("已保存：teamwork"), WAIT, "玩家明确设置偏好");
        MODEL.whenHeard("read player preference from chat", lua("local c = sceneadapt.player.context(); "
                + "print(\"SCENEADAPT_CHAT:\" .. c.preferences[1].value)"), say("Chat preference read."));
        mark = owner.mark();
        owner.say("read player preference from chat");
        owner.await(mark, m -> m.text().contains("Chat preference read."), WAIT, "普通聊天栏调用扩展");
        assertModelSawToolResult("SCENEADAPT_CHAT:ask before taking over");

        mark = owner.mark();
        owner.command("numen sceneadapt memory clear confirm");
        owner.await(mark, m -> m.text().contains("已清除 SceneAdapt"), WAIT, "玩家清空扩展数据");
        MODEL.whenHeard("check cleared player context", lua("local c = sceneadapt.player.context(); "
                + "print(\"SCENEADAPT_CLEARED:\" .. #c.preferences .. \":\" .. #c.pending .. \":\" .. tostring(c.division == nil))"),
                say("Cleared context inspected."));
        mark = owner.mark();
        owner.say("check cleared player context");
        owner.await(mark, m -> m.text().contains("Cleared context inspected."), WAIT, "清空后仍可正常交互");
        assertModelSawToolResult("SCENEADAPT_CLEARED:0:0:true");
    }
}
