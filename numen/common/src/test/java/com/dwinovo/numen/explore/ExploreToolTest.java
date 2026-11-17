package com.dwinovo.numen.explore;

import com.dwinovo.numen.api.sdk.SceneCamera;
import com.dwinovo.numen.api.agent.tool.ToolCall;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ExploreToolTest {
    @Test void failed_steps_have_a_complete_envelope_without_invented_coordinates(){
        var result=ExploreTool.summary("no_candidates","REPLANNING",new Vec3(1,70,2));
        assertTrue(com.dwinovo.numen.api.agent.llm.ToolOutcome.failed(result.toString()));
        for(String field:java.util.List.of("target_position","target_direction","path_cost","execution_plan"))assertTrue(result.get(field).isJsonNull());
        assertEquals(0,result.getAsJsonArray("candidate_candidates").size());
        assertTrue(ExploreTool.summary("body_unavailable","REPLANNING",null).get("current_position").isJsonNull());
    }
    @Test void moved_body_or_changed_dimension_invalidates_camera_coordinates(){
        var frame=new SceneCamera.View(null,new Vec3(10,65,20),90,0,"minecraft:overworld");
        assertTrue(ExploreTool.frameMatches(frame,new Vec3(10.1,65,20),"minecraft:overworld"));
        assertFalse(ExploreTool.frameMatches(frame,new Vec3(11,65,20),"minecraft:overworld"));
        assertFalse(ExploreTool.frameMatches(frame,new Vec3(10,65,20),"minecraft:the_nether"));
        assertFalse(ExploreTool.frameMatches(null,new Vec3(10,65,20),"minecraft:overworld"));
    }
    @Test void cancellation_is_visible_before_late_perception_can_start_movement(){
        var alive=new AtomicBoolean(true);
        var call=new ToolCall("test","explore","{}",UUID::randomUUID,result->{},alive::get);
        assertTrue(call.isActive());alive.set(false);assertFalse(call.isActive());
    }
    @Test void tool_rejects_raw_actions_and_unsupported_semantics(){
        var tool=new ExploreTool();
        assertThrows(IllegalArgumentException.class,()->tool.invoke(new ToolCall("test","explore","{\"code\":\"teleport()\"}",UUID::randomUUID,r->{})));
        assertThrows(IllegalArgumentException.class,()->tool.invoke(new ToolCall("test","explore","{\"reason\":\"find\",\"goal\":\"cave\"}",UUID::randomUUID,r->{})));
        assertEquals("explore",tool.name());
    }
}
