package com.dwinovo.numen.plugins.sceneadapt;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.BiConsumer;

/** 玩家侧可点击管理卡片的落点。不要求 OP，身份只取发起指令的真实玩家。 */
final class PlayerCommands {
    private static final String ROOT = "/numen sceneadapt ";
    private PlayerCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> tree(NumenApi api) {
        return Commands.literal("sceneadapt")
                .requires(s -> s.getEntity() instanceof ServerPlayer && !(s.getEntity() instanceof NumenPlayer))
                .executes(ctx -> run(ctx, (owner, store) -> show(owner, store)))
                .then(Commands.literal("memory")
                        .executes(ctx -> run(ctx, PlayerCommands::show))
                        .then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx -> run(ctx, (owner, store) -> {
                                    PlayerStore.Fact fact = store.remember(arg(ctx, "key"), arg(ctx, "value"));
                                    say(owner, "已保存：" + fact.key() + " = " + fact.value());
                                    NumenSceneAdapt.changed(api, owner, "Owner updated a confirmed preference");
                                })))))
                        .then(Commands.literal("forget").then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (owner, store) -> {
                                    store.forget(arg(ctx, "key"));
                                    say(owner, "已删除该偏好和相关未确认提议。");
                                    NumenSceneAdapt.changed(api, owner, "Owner revoked a preference; do not keep applying its previous value");
                                }))))
                        .then(Commands.literal("clear").then(Commands.literal("confirm").executes(ctx -> run(ctx, (owner, store) -> {
                            store.clear();
                            say(owner, "已清除 SceneAdapt 偏好、提议、分工和反馈；Numen 原生会话与札记未删除。");
                            NumenSceneAdapt.changed(api, owner, "Owner cleared all SceneAdapt preferences, divisions and feedback; previous extension data is revoked");
                        })))))
                .then(Commands.literal("proposal")
                        .then(Commands.literal("accept").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (owner, store) -> {
                                    PlayerStore.Proposal p = store.accept(arg(ctx, "id"));
                                    say(owner, "已确认" + (p.kind() == PlayerStore.Kind.PREFERENCE ? "玩家偏好" : "协作分工") + "：" + p.value());
                                    NumenSceneAdapt.changed(api, owner, "Owner approved " + p.kind() + " proposal " + p.id());
                                }))))
                        .then(Commands.literal("reject").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (owner, store) -> {
                                    store.reject(arg(ctx, "id"));
                                    say(owner, "提议已拒绝，不会成为长期偏好或协作分工。");
                                    NumenSceneAdapt.changed(api, owner, "Owner rejected proposal " + arg(ctx, "id") + "; it is not an instruction");
                                })))))
                .then(Commands.literal("task")
                        .then(Commands.argument("companion", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (owner, store) -> showTask(owner, store, companion(ctx, owner))))
                                .then(Commands.literal("finish").executes(ctx -> run(ctx, (owner, store) -> {
                                    UUID id = companion(ctx, owner);
                                    store.finishContract(id);
                                    say(owner, "玩家已结束该分工。这不是建筑完成或物资入包的自动验收。");
                                    NumenSceneAdapt.changed(api, owner, "Owner ended the shared division for " + id);
                                })))))
                .then(Commands.literal("feedback")
                        .then(Commands.literal("helpful").executes(ctx -> feedback(ctx, api, true)))
                        .then(Commands.literal("intrusive").executes(ctx -> feedback(ctx, api, false))));
    }

    private static UUID companion(CommandContext<CommandSourceStack> ctx, ServerPlayer owner) {
        String input = arg(ctx, "companion");
        for (ServerPlayer player : owner.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())
                    && (body.getName().getString().equalsIgnoreCase(input) || body.getUUID().toString().equals(input))) {
                return body.getUUID();
            }
        }
        throw PlayerStore.invalid("找不到你名下在线的这个同伴；可填名称或 UUID");
    }

    private static int feedback(CommandContext<CommandSourceStack> ctx, NumenApi api, boolean helpful) throws CommandSyntaxException {
        return run(ctx, (owner, store) -> {
            PlayerStore.Feedback result = store.feedback(helpful);
            say(owner, "已记录" + (helpful ? "这次帮助合适" : "这次过于主动") + "；反馈均值 "
                    + String.format(java.util.Locale.ROOT, "%.3f", result.posteriorMean()) + "，不代表游戏操作权限。");
            NumenSceneAdapt.changed(api, owner, helpful ? "Owner rated the assistance helpful" : "Owner rated the assistance intrusive; ask before taking over optional work");
        });
    }

    private static int run(CommandContext<CommandSourceStack> ctx, BiConsumer<ServerPlayer, PlayerStore> action) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        try {
            action.accept(owner, Cooperation.forOwner(owner.getServer(), owner.getUUID()));
            return 1;
        } catch (IllegalArgumentException | UncheckedIOException e) {
            ctx.getSource().sendFailure(Component.literal("SceneAdapt：" + e.getMessage()));
            return 0;
        }
    }

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) { return StringArgumentType.getString(ctx, name); }

    private static void show(ServerPlayer owner, PlayerStore store) {
        PlayerStore.View view = store.view(null, "");
        say(owner, "玩家协作资料 v" + view.revision() + "：" + view.preferences().size() + " 条确认记忆，" + view.pending().size() + " 条待确认提议。");
        for (PlayerStore.Fact fact : view.preferences()) {
            owner.sendSystemMessage(Component.literal(fact.key() + " = " + fact.value() + " [" + fact.source() + "] ")
                    .append(button("[删除]", ROOT + "memory forget " + fact.key(), ChatFormatting.RED)));
        }
        for (PlayerStore.Proposal p : view.pending()) showProposal(owner, p);
        for (ServerPlayer p : owner.getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())) showTask(owner, store, body.getUUID());
        }
        owner.sendSystemMessage(Component.literal("对本次协作反馈： ")
                .append(button("[合适] ", ROOT + "feedback helpful", ChatFormatting.GREEN))
                .append(button("[太主动]", ROOT + "feedback intrusive", ChatFormatting.YELLOW)));
    }

    private static void showTask(ServerPlayer owner, PlayerStore store, UUID companion) {
        PlayerStore.Contract c = store.view(companion, "").contract();
        if (c == null) { say(owner, companion + "：尚无确认分工。"); return; }
        owner.sendSystemMessage(Component.literal("分工 v" + c.revision() + "：" + c.goal() + "；玩家：" + c.playerRole()
                + "；NPC：" + c.npcRole() + " ")
                .append(button("[结束分工]", ROOT + "task " + companion + " finish", ChatFormatting.YELLOW)));
    }

    static void showProposal(ServerPlayer owner, PlayerStore.Proposal proposal) {
        String detail = proposal.kind() == PlayerStore.Kind.PREFERENCE
                ? proposal.key() + " = " + proposal.value() + "；依据：" + proposal.evidence()
                : proposal.value() + "；玩家：" + proposal.playerRole() + "；NPC：" + proposal.npcRole();
        owner.sendSystemMessage(Component.literal("[SceneAdapt 待确认] " + detail + " ")
                .append(button("[接受] ", ROOT + "proposal accept " + proposal.id(), ChatFormatting.GREEN))
                .append(button("[拒绝]", ROOT + "proposal reject " + proposal.id(), ChatFormatting.RED)));
    }

    private static MutableComponent button(String text, String command, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void say(ServerPlayer owner, String text) { owner.sendSystemMessage(Component.literal("[SceneAdapt] " + text)); }
}
