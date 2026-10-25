package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真死亡、原生五秒复活与同一个 UUID 的模型循环;不直接调用大脑的死亡/复活回调。 */
class RespawnTest extends Scenario {

    @Test
    void death_during_a_program_automatically_restores_the_body_and_native_tools() throws Exception {
        Bot owner = join("e2e_mortal", "zh_cn");
        summon(owner, "Mortal");
        String identity = console("data get entity Mortal UUID");
        MODEL.whenHeard("wait through a death", lua("numen.time.wait(30)"), say("OLD_PROGRAM_FINISHED"));
        MODEL.whenHeard("kind=\"death\"", lua("print('RESPAWN_NATIVE_TOOL_OK')"), say("NATIVE_BRAIN_REVIVED"));
        int mark = owner.mark();
        owner.command("msg Mortal wait through a death");
        owner.await(mark, m -> m.overlay() && m.text().contains("Mortal"), WAIT, "程序运行中的动作栏");
        assertTrue(MODEL.wasToldAbout("wait through a death"));

        mark = owner.mark();
        assertTrue(console("kill Mortal").contains("Mortal"));
        owner.await(mark, m -> m.text().contains("NATIVE_BRAIN_REVIVED"), WAIT, "无需再次召唤或说话的原生大脑复活");

        assertEquals(identity, console("data get entity Mortal UUID"), "复活不能创建新的同伴身份");
        assertOneBody("Mortal");
        assertTrue(MODEL.asked().stream().flatMap(a -> a.toolResults().stream())
                .anyMatch(text -> text.contains("RESPAWN_NATIVE_TOOL_OK")), "复活后的原生工具没有执行并回填");
        assertTrue(MODEL.asked().stream().anyMatch(a -> a.fromHuman()
                && a.lastText().contains("kind=\"death\"") && a.lastText().contains("Mortal")), "死亡原因没有交给原来的大脑");
        assertFalse(owner.messagesSince(mark).stream().anyMatch(m -> m.text().contains("OLD_PROGRAM_FINISHED")),
                "死亡前的程序不应继续回复");

        // 第二次死亡检验的是新身体,不能沿用上一具身体的去重位。
        MODEL.whenHeard("kind=\"death\"", say("SECOND_NATIVE_REVIVAL"));
        mark = owner.mark();
        assertTrue(console("kill Mortal").contains("Mortal"));
        owner.await(mark, m -> m.text().contains("SECOND_NATIVE_REVIVAL"), WAIT, "同一同伴第二次自动复活");
        assertEquals(identity, console("data get entity Mortal UUID"));
        assertOneBody("Mortal");
    }

    @Test
    void summoning_during_the_death_window_cannot_bypass_the_native_respawn() throws Exception {
        Bot owner = join("e2e_resummon", "en_us");
        summon(owner, "Reviva");
        String identity = console("data get entity Reviva UUID");
        MODEL.whenHeard("kind=\"death\"", say("TIMED_NATIVE_REVIVAL"));
        int mark = owner.mark();

        assertTrue(console("kill Reviva").contains("Reviva"));
        awaitAbsent("Reviva");
        // 用主人的指令来源同步重召,在五秒窗口内检验身体仍由原生定时器负责。
        console("execute as @a[name=e2e_resummon] at @s run numen player summon Reviva");
        assertFalse(console("list").contains("Reviva"), "通用休眠恢复在死亡窗口内另造了身体");

        owner.await(mark, m -> m.text().contains("TIMED_NATIVE_REVIVAL"), WAIT, "定时复活仍清登记并解开 DEAD");
        assertEquals(identity, console("data get entity Reviva UUID"));
        assertOneBody("Reviva");
        MODEL.whenHeard("can you still answer", say("STILL_THE_SAME_BRAIN"));
        mark = owner.mark();
        owner.command("msg Reviva can you still answer");
        owner.await(mark, m -> m.text().contains("STILL_THE_SAME_BRAIN"), WAIT, "复活后沿用原绑定继续交互");
    }

    private static void assertOneBody(String name) throws Exception {
        assertEquals(1L, Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(console("list")).results().count(),
                "玩家列表中必须恰好有一具同名身体");
    }

    private static void awaitAbsent(String name) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (console("list").contains(name)) {
            assertTrue(System.nanoTime() < deadline, "死亡身体没有退场");
            Thread.sleep(50);
        }
    }
}
