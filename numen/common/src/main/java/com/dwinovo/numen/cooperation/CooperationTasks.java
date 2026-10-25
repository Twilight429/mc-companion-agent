package com.dwinovo.numen.cooperation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** 合作清单的类型和只读进度;执行仍由各任务模块与原生身体任务槽负责。 */
public final class CooperationTasks {
    private CooperationTasks() {}
    public record Kind(String id,String title,String icon) {}
    public record Task(String id,String kind,String title,String companionId,String companion,
                       String phase,int done,int total,String note,long updated) {
        public boolean active() { return phase.equals("active"); }
    }
    private static final Map<String,Kind> KINDS=new LinkedHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,LinkedHashMap<String,Task>>> TASKS=new WeakHashMap<>();
    static {
        register(new Kind("construction","建筑任务","minecraft:bricks"));
        com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(TASKS::clear);
    }
    public static void register(Kind kind) {
        if(kind.id().isBlank()||kind.title().isBlank()||net.minecraft.resources.ResourceLocation.tryParse(kind.icon())==null)
            throw new IllegalArgumentException("invalid cooperation task type");
        var old=KINDS.putIfAbsent(kind.id(),kind);
        if(old!=null&&!old.equals(kind)) throw new IllegalArgumentException("duplicate cooperation task type");
    }
    public static List<Kind> kinds() { return List.copyOf(KINDS.values()); }
    public static void publish(ServerPlayer owner,Task task) {
        if(!KINDS.containsKey(task.kind())) throw new IllegalArgumentException("unregistered task type");
        var history=TASKS.computeIfAbsent(owner.server,s->new HashMap<>()).computeIfAbsent(owner.getUUID(),id->new LinkedHashMap<>());
        history.put(task.id(),task);
        while(history.values().stream().filter(t->!t.active()).count()>32) {
            String oldest=history.values().stream().filter(t->!t.active()).min(Comparator.comparingLong(Task::updated)).orElseThrow().id();
            history.remove(oldest);
        }
    }
    public static List<Task> list(ServerPlayer owner) {
        var byOwner=TASKS.get(owner.server);
        if(byOwner==null||!byOwner.containsKey(owner.getUUID())) return List.of();
        return byOwner.get(owner.getUUID()).values().stream().sorted(Comparator.comparing(Task::active).reversed()
                .thenComparing(Comparator.comparingLong(Task::updated).reversed())).toList();
    }
}
