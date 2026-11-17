package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她要做一件主人显式设置为征询的事:没装客户端的主人在聊天栏里收到一条带三个可点按钮的消息,
 * 点「允许」(把按钮里的命令敲出去)以后她接着做完,并告诉他。
 */
class ConsentTest extends Scenario {

    @Test
    void a_consent_request_is_a_clickable_message_and_allowing_it_lets_her_carry_on() throws Exception {
        Bot owner = join("e2e_approver", "en_us");
        summon(owner, "Rin");
        assertTrue(console("give Rin minecraft:dirt 4").contains("Rin"), "the dirt was not given");
        int setup = owner.mark();
        owner.command("numen permission rules add ask drop(minecraft:dirt)");
        owner.await(setup, m -> m.text().contains("drop(minecraft:dirt)"), WAIT, "主人显式安装询问规则");
        MODEL.whenHeard("drop the dirt", lua("numen.inv.drop(\"minecraft:dirt\", {count = 4})"),
                say("Dropped the dirt."));
        int mark = owner.mark();

        owner.command("msg Rin drop the dirt");

        Bot.Message ask = owner.await(mark, m -> !Text.clickCommands(m.content()).isEmpty(), WAIT, "可点击的征询消息");
        List<String> buttons = Text.clickCommands(ask.content());
        assertEquals(3, buttons.size(), "the request should offer allow, always allow and deny: " + buttons);
        assertTrue(buttons.get(0).startsWith("/numen consent allow ") && buttons.get(1).startsWith("/numen consent remember ")
                && buttons.get(2).startsWith("/numen consent deny "), "unexpected buttons: " + buttons);
        assertTrue(ask.text().contains("Rin") && ask.text().contains("drop") && !ask.text().contains("numen.consent"),
                "the request does not read as a question in his language: " + ask.text());

        owner.click(buttons.get(0));

        owner.await(mark, m -> "commands.message.display.incoming".equals(Text.key(m.content()))
                && Text.arguments(m.content()).contains("Dropped the dirt."), WAIT, "她做完后的回话");
        assertTrue(console("clear Rin minecraft:dirt 0").contains("No items"),
                "the dirt is still in her inventory after she was allowed to drop it");
    }
}
