package com.dwinovo.numen.plugins.sceneadapt;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** 玩家协作上下文与提议；不扫描、不移动、不修改 Numen 的任务槽。 */
public final class PlayerApi {
    private PlayerApi() {}

    public record Query(@Doc("Current task words; only changes the ordering of confirmed preferences.")
                        @Omitted("return confirmed preferences in newest-first order") Optional<String> query) {}
    public record Memory(@Doc("Stable preference key.") String key, @Doc("Owner-approved preference.") String value,
                         @Doc("Original evidence, not an instruction or permission.") String evidence,
                         @Doc("owner or owner_confirmed.") String source) {}
    public record Division(@Doc("Owner-approved shared goal.") String goal,
                           @Doc("What the player is doing.") String playerRole,
                           @Doc("What this companion agreed to do.") String npcRole,
                           @Doc("Version of this division.") long revision) {}
    public record Feedback(@Doc("Explicit helpful votes.") long helpful, @Doc("Explicit intrusive votes.") long intrusive,
                           @Doc("Beta(2,2) posterior mean; not a permission or calibrated personality score.") double mean) {}
    public record Context(@Doc("Authenticated owner UUID; never supplied by the model.") String owner,
                          @Doc("Version of the owner's extension data.") long revision,
                          @Doc("Confirmed preferences. Latest player instructions take priority.") List<Memory> preferences,
                          @Doc("Confirmed division for this companion, if any.") Optional<Division> division,
                          @Doc("Pending proposals, not accepted instructions.") List<String> pending,
                          @Doc("Owner feedback on assistance, separate from task success.") Feedback feedback) {}
    public record MemoryProposal(@Doc("A short stable preference key, e.g. mining_style.") String key,
                                 @Doc("The proposed preference. Never include API keys or secrets.") String value,
                                 @Doc("The owner's actual words supporting it; do not invent evidence.") String evidence) {}
    public record TaskProposal(@Doc("A concrete shared goal, not a claim of completion.") String goal,
                               @Doc("The player's role in the proposed task.") String playerRole,
                               @Doc("This companion's role in the proposed task.") String npcRole) {}
    public record Proposed(@Doc("Proposal id; only the owner can accept it.") String id,
                           @Doc("Always pending_owner_confirmation; proposing never starts a body task.") String status) {}

    @Fn("Read confirmed player preferences, this companion's shared task and explicit assistance feedback.")
    @Example("sceneadapt.player.context()")
    public static Context context(ServerCall call, Query args) {
        return checked(() -> context(Cooperation.forBody(call.her()).view(call.companion(), args.query.orElse(""))));
    }

    @Fn("Propose a long-term player preference for owner approval; this does not save a confirmed memory.")
    @Example("sceneadapt.player.propose_memory(\"mining_style\", \"Let me find the ore first\", \"The owner said: let me find the ore first\")")
    public static Proposed proposeMemory(ServerCall call, MemoryProposal args) {
        return checked(() -> offer(call.her(), Cooperation.forBody(call.her())
                .proposeMemory(call.companion(), args.key, args.value, args.evidence)));
    }

    @Fn("Propose a persistent shared goal and division of labor; only owner approval makes it active. Does not start or stop body tasks.")
    @Example("sceneadapt.player.propose_task(\"Build a shelter\", \"Design and place walls\", \"Bring planks, do not build the walls\")")
    public static Proposed proposeTask(ServerCall call, TaskProposal args) {
        return checked(() -> offer(call.her(), Cooperation.forBody(call.her())
                .proposeContract(call.companion(), args.goal, args.playerRole, args.npcRole)));
    }

    private static Proposed offer(NumenPlayer body, PlayerStore.Proposal proposal) {
        if (body.resolveOwnerPlayer() != null) PlayerCommands.showProposal(body.resolveOwnerPlayer(), proposal);
        return new Proposed(proposal.id(), "pending_owner_confirmation");
    }

    static Context context(PlayerStore.View view) {
        List<Memory> memories = view.preferences().stream()
                .map(f -> new Memory(f.key(), f.value(), f.evidence(), f.source())).toList();
        Optional<Division> task = Optional.ofNullable(view.contract()).map(c -> new Division(c.goal(), c.playerRole(), c.npcRole(), c.revision()));
        return new Context(view.owner().toString(), view.revision(), memories, task,
                view.pending().stream().map(p -> p.id() + ": " + p.kind() + " " + p.value()).toList(),
                new Feedback(view.feedback().helpful(), view.feedback().intrusive(), view.feedback().posteriorMean()));
    }

    private static <T> T checked(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException | UncheckedIOException e) {
            throw new ApiError(ErrorKind.FAILED, e.getMessage(), null);
        }
    }
}
