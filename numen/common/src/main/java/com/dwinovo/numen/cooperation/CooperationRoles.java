package com.dwinovo.numen.cooperation;

import java.util.Set;

/** 当前任务的角色证据,不是人格或长期玩家意图。手动锁定优先,采集与建造可以同时成立。 */
public final class CooperationRoles {
    public static final Set<String> MODES = Set.of("auto", "collector", "builder", "both", "idle");
    private CooperationRoles() {}
    public record Evidence(boolean collected, boolean built, boolean mining, boolean emptyStock,
                           boolean shortOf, int nearTicks, boolean complete) {}
    public record Decision(String role, double confidence, String evidence) {}
    public static String mode(String role) {
        if (!MODES.contains(role)) throw new IllegalArgumentException("role must be auto, collector, builder, both or idle");
        return role;
    }
    public static Decision player(String manual, Evidence e) {
        mode(manual);
        if (!manual.equals("auto")) return new Decision(manual, 1, "manual_locked");
        if (e.complete()) return new Decision("idle", 1, "verified_scope_complete");
        if (e.collected() && e.built()) return new Decision("both", .95, "recent_collection_and_placement");
        if (e.built()) return new Decision("builder", .95, "observed_placement");
        if (e.collected() || e.mining()) return new Decision("collector", .9, "observed_collection_or_mining");
        if (e.emptyStock() && e.shortOf()) return new Decision("collector", .7, "no_materials_available");
        if (e.nearTicks() >= 120) return new Decision("builder", .45, "proximity_only_not_confirmed_intent");
        return new Decision("idle", .2, "insufficient_evidence");
    }
    public static Decision companion(String manual, Decision player, boolean emptyStock, boolean missing, boolean complete) {
        mode(manual);
        if (!manual.equals("auto")) return new Decision(manual, 1, "manual_locked");
        if (complete) return new Decision("idle", 1, "verified_scope_complete");
        if (emptyStock && missing) return new Decision("collector", .9, "both_can_collect_when_stock_is_empty");
        if (missing && (player.role().equals("builder") || player.role().equals("both")))
            return new Decision("collector", .8, "player_builds_companion_replenishes");
        return new Decision("both", .8, "fetch_from_shared_stock_then_build_in_batches");
    }
    public static boolean builds(String role) { return role.equals("builder") || role.equals("both"); }
    public static Decision assigned(String manual,String plan,Decision observed,boolean completedOrUnavailable) {
        mode(manual); mode(plan);
        if(!manual.equals("auto")||plan.equals("auto")||completedOrUnavailable||observed.confidence()>=.9) return observed;
        return new Decision(plan,.8,"planner_assignment_subject_to_observed_changes");
    }
    public static int shortfall(int remaining, int player, int companion, int chest) {
        if (remaining < 0 || player < 0 || companion < 0 || chest < 0) throw new IllegalArgumentException("negative stock");
        return (int)Math.max(0L, (long)remaining - player - companion - chest);
    }
}
