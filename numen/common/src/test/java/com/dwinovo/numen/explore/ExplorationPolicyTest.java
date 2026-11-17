package com.dwinovo.numen.explore;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationPolicyTest {
    private ExplorationPolicy.Options options(String json){return ExplorationPolicy.options(JsonParser.parseString(json).getAsJsonObject());}
    @Test void legacy_goal_calls_keep_an_explicit_mode_and_bounded_defaults(){
        var resource=options("{\"reason\":\"find water\",\"goal\":\"water\"}");
        assertEquals("locate",resource.mode());assertEquals(96,resource.maxDistance());assertEquals(45,resource.maxDuration());assertEquals(4,resource.maxWaypoints());
        assertEquals("frontier_exploration",options("{\"goal\":\"terrain\"}").mode());
    }
    @Test void unknown_resource_is_never_invented(){
        assertThrows(IllegalArgumentException.class,()->options("{\"mode\":\"locate\"}"));
        assertThrows(IllegalArgumentException.class,()->options("{\"goal\":\"any\",\"mode\":\"reach\"}"));
        assertThrows(IllegalArgumentException.class,()->options("{\"goal\":\"cave\"}"));
        assertThrows(IllegalArgumentException.class,()->options("{\"goal\":\"water\",\"mode\":\"frontier_exploration\"}"));
    }
    @Test void sessions_cannot_request_unlimited_duration_or_distance(){
        for(String pair:List.of("\"max_distance\":0","\"max_distance\":257","\"max_duration\":121","\"max_duration\":0","\"max_waypoints\":13","\"max_waypoints\":2.5","\"max_duration\":\"NaN\"","\"max_distance\":\"Infinity\""))
            assertThrows(IllegalArgumentException.class,()->options("{\"goal\":\"water\","+pair+"}"));
    }
    @Test void explicit_longer_session_is_accepted_without_changing_the_default(){
        var o=options("{\"goal\":\"wood\",\"mode\":\"reach\",\"max_duration\":90,\"max_distance\":160,\"max_waypoints\":8}");
        assertEquals("reach",o.mode());assertEquals(90,o.maxDuration());assertEquals(160,o.maxDistance());assertEquals(8,o.maxWaypoints());
    }
    @Test void movement_budget_counts_the_walk_not_only_net_displacement(){
        var travel=new ExplorationPolicy.Travel(Vec3.ZERO);travel.sample(new Vec3(3,0,0),1);travel.sample(new Vec3(3,0,4),1);travel.sample(Vec3.ZERO,1);
        assertEquals(12,travel.length(),1e-8);
    }
    @Test void progress_watchdog_recovers_when_body_moves(){
        var travel=new ExplorationPolicy.Travel(Vec3.ZERO);travel.sample(Vec3.ZERO,7);assertEquals(7,travel.stillSeconds());
        travel.sample(new Vec3(1,0,0),.05);assertEquals(0,travel.stillSeconds());
    }
    @Test void distant_waypoints_use_world_yaw_and_do_not_reuse_the_eight_block_projection(){
        var from=new Vec3(100,70,200);assertEquals(new Vec3(68,70,200),ExplorationPolicy.project(from,90,32));
        assertEquals(90,ExplorationPolicy.yaw(from,new Vec3(68,70,200)));assertEquals(2,ExplorationPolicy.angle(359,1));
    }
    @Test void visited_regions_reject_oscillation_but_allow_new_frontiers(){
        var visited=List.of(Vec3.ZERO,new Vec3(32,0,0));
        assertTrue(ExplorationPolicy.revisited(new Vec3(2,20,1),visited));assertFalse(ExplorationPolicy.revisited(new Vec3(64,0,0),visited));
    }
    @Test void planned_length_includes_detours_and_vertical_motion(){
        assertEquals(12,ExplorationPolicy.routeLength(List.of(new BlockPos(0,0,0),new BlockPos(3,0,0),new BlockPos(3,4,0),new BlockPos(0,0,0))),1e-8);
    }
    @Test void execution_payloads_are_not_accepted_as_exploration_parameters(){
        assertThrows(IllegalArgumentException.class,()->options("{\"goal\":\"water\",\"code\":\"teleport()\"}"));
    }
}
