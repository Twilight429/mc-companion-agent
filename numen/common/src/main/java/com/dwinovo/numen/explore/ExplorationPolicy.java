package com.dwinovo.numen.explore;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

/** 会话参数和距离账:只定探索预算,不替权限层决定能否改变世界。 */
final class ExplorationPolicy {
    private ExplorationPolicy() {}
    static final Set<String> GOALS=Set.of("any","terrain","water","wood","leaves","ore","constructed","finish");
    record Options(String goal,String mode,double maxDistance,double maxDuration,int maxWaypoints) {}
    static Options options(JsonObject a) {
        if(!Set.of("reason","goal","mode","max_distance","max_duration","max_waypoints").containsAll(a.keySet()))
            throw new IllegalArgumentException("unknown exploration parameter");
        String goal=a.has("goal")?a.get("goal").getAsString():"";
        if(!GOALS.contains(goal))throw new IllegalArgumentException("explicit supported goal required");
        if(a.has("reason")&&(a.get("reason").getAsString().isBlank()||a.get("reason").getAsString().length()>300))
            throw new IllegalArgumentException("reason must be 1..300 characters");
        String mode=a.has("mode")?a.get("mode").getAsString():Set.of("any","terrain","finish").contains(goal)?"frontier_exploration":"locate";
        if(!Set.of("locate","reach","frontier_exploration").contains(mode))throw new IllegalArgumentException("unsupported exploration mode");
        if(Set.of("locate","reach").contains(mode)&&Set.of("any","terrain").contains(goal))
            throw new IllegalArgumentException("locate/reach require a resource goal; use frontier_exploration for unknown areas");
        if(mode.equals("frontier_exploration")&&!Set.of("any","terrain","finish").contains(goal))
            throw new IllegalArgumentException("resource goals require locate or reach; frontier_exploration uses any/terrain");
        double distance=number(a,"max_distance",96,4,256),duration=number(a,"max_duration",45,5,120);
        double count=number(a,"max_waypoints",4,1,12);
        if(count!=Math.rint(count))throw new IllegalArgumentException("max_waypoints must be an integer");
        return new Options(goal,mode,distance,duration,(int)count);
    }
    private static double number(JsonObject a,String key,double defaultValue,double min,double max) {
        double n=a.has(key)?a.get(key).getAsDouble():defaultValue;
        if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" must be in "+min+".."+max);
        return n;
    }
    static double yaw(Vec3 from,Vec3 to){return Math.toDegrees(Math.atan2(from.x-to.x,to.z-from.z));}
    static double angle(double a,double b){return Math.abs(Math.IEEEremainder(a-b,360));}
    static Vec3 project(Vec3 from,double yaw,double distance){double r=Math.toRadians(yaw);return from.add(-Math.sin(r)*distance,0,Math.cos(r)*distance);}
    static boolean revisited(Vec3 target,List<Vec3> visited){return visited.stream().anyMatch(p->horizontal(target,p)<8);}
    static double horizontal(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    static double routeLength(List<net.minecraft.core.BlockPos> nodes){double length=0;for(int i=1;i<nodes.size();i++)length+=Math.sqrt(nodes.get(i-1).distSqr(nodes.get(i)));return length;}
    static final class Travel {
        private Vec3 last;private double length;private double stillSeconds;
        Travel(Vec3 start){last=start;}
        void sample(Vec3 current,double seconds){double d=last.distanceTo(current);length+=d;stillSeconds=d<.015?stillSeconds+seconds:0;last=current;}
        double length(){return length;}
        double stillSeconds(){return stillSeconds;}
    }
}
