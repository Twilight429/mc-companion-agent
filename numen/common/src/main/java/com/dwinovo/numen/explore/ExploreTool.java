package com.dwinovo.numen.explore;

import com.dwinovo.numen.api.agent.tool.*;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.sdk.SceneCamera;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.task.TaskDispatch;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.dwinovo.numen.api.task.TaskRecord;
import com.dwinovo.numen.task.move.MoveToTaskRecord;
import com.dwinovo.numen.task.move.Arrival;
import com.dwinovo.numen.task.move.Where;
import com.dwinovo.numen.nav.Survey;
import com.dwinovo.numen.nav.Walking;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.scan.BlockSearch;
import com.dwinovo.numen.scan.BlockScanner;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 目标驱动的有界探索会话:航点之间由本工具重新感知,结束后才交回大脑。 */
public final class ExploreTool implements NumenTool {
    private static MinecraftServer server;
    private static final Map<UUID,String> MODES=new ConcurrentHashMap<>();
    private static final Map<UUID,Session> ACTIVE=new HashMap<>();
    static {
        ServerLifecycle.onStarted(s->server=s);
        ServerLifecycle.onStopped(()->{
            for(Session session:List.copyOf(ACTIVE.values()))session.finish("server_stopped");
            ACTIVE.clear();MODES.clear();server=null;
        });
    }
    public static String state(NumenPlayer her) {
        if(!ACTIVE.containsKey(her.getUUID())&&CompanionTickDispatcher.currentTaskFor(her.getUUID())!=null)
            MODES.put(her.getUUID(),"NORMAL_TASK");
        return "<exploration mode=\""+MODES.getOrDefault(her.getUUID(),"NORMAL_TASK")+"\" position=\""+her.getX()+","+her.getY()+","+her.getZ()+"\" dimension=\""+her.level().dimension().location()+"\"/>";
    }
    public String name(){return "explore";}
    public String description(){return "Run ONE bounded goal-driven exploration session, internally capturing fresh views and moving through multiple native-validated waypoints. Use locate/reach with a resource goal, or frontier_exploration for unknown areas. No per-waypoint LLM calls or images sent to the LLM. Returns final session ledger and REPLANNING; goal finish restores NORMAL_TASK. Load skill visual_exploration.";}
    public Map<String,Object> parameterSchema(){return Schema.object()
        .enumStr("goal","Explicit resource goal; any/terrain for frontier, finish to end.","any","terrain","water","wood","leaves","ore","constructed","finish")
        .optionalString("reason","Task-level reason, 1..300 characters.")
        .optionalEnum("mode","locate finds native-confirmed coordinates; reach moves near them; frontier explores unknown areas.","locate","reach","frontier_exploration")
        .optionalNumber("max_distance","Cumulative actual travel budget in blocks; default 96.",4,256)
        .optionalNumber("max_duration","Whole session wall-clock seconds; default 45, hard cap 120.",5,120)
        .optionalInteger("max_waypoints","Maximum native movement attempts in this one tool call; default 4.",1,12).build();}
    public void invoke(ToolCall call) {
        var options=ExplorationPolicy.options(call.args());
        MinecraftServer s=server;
        if(s==null){call.complete(summary("server_unavailable","REPLANNING",null).toString());return;}
        s.execute(()->{
            NumenPlayer her=NumenPlayer.findByUuid(s,call.ctx().entityUuid());
            if(her==null||!her.isAlive()||her.getOwnerUuid()==null){call.complete(summary("body_unavailable","REPLANNING",null).toString());return;}
            if(ACTIVE.containsKey(her.getUUID())||bodyBusy(her)){call.complete(summary("busy",MODES.getOrDefault(her.getUUID(),"NORMAL_TASK"),her.position()).toString());return;}
            if(options.goal().equals("finish")){MODES.put(her.getUUID(),"NORMAL_TASK");call.complete(summary("ended","NORMAL_TASK",her.position()).toString());return;}
            Session session=new Session(s,her,call,options);ACTIVE.put(her.getUUID(),session);MODES.put(her.getUUID(),"EXPLORATION");session.start();
        });
    }
    private static boolean bodyBusy(NumenPlayer her){var task=CompanionTickDispatcher.currentTaskFor(her.getUUID());return task!=null&&!task.getState().isTerminal();}
    private static final class Session {
        final MinecraftServer world;final NumenPlayer her;final ToolCall call;final ExplorationPolicy.Options options;
        final long started=System.nanoTime();final String dimension;final Vec3 initial;final float health;
        final CancelToken cancel=new CancelToken();final ExplorationPolicy.Travel travel;
        final List<Vec3> visited=new ArrayList<>(),stalledTargets=new ArrayList<>();final List<Double> headings=new ArrayList<>();
        final JsonArray history=new JsonArray();final Set<Block> targets;
        JsonArray candidates=new JsonArray();JsonObject step;SceneCamera.View frame;Survey survey;
        MoveToTaskRecord movement;BlockPos target,resource;int searchId=-1,index,observations,attempts,completed,turns,failures;
        long phaseStarted,planStarted,lastSample=System.nanoTime(),moveStarted;double moveDistance,plannedTotal;
        boolean done,goalFound,goalReached;String stopReason;List<BlockScanner.Hit> hits=List.of();
        Session(MinecraftServer world,NumenPlayer her,ToolCall call,ExplorationPolicy.Options options){
            this.world=world;this.her=her;this.call=call;this.options=options;dimension=her.level().dimension().location().toString();
            initial=her.position();health=her.getHealth();travel=new ExplorationPolicy.Travel(initial);visited.add(initial);targets=targetBlocks(options.goal());
        }
        void start(){log("session_start",pos(initial));watch();observe();}
        double elapsed(){return (System.nanoTime()-started)/1e9;}
        double remaining(){return options.maxDistance()-travel.length();}
        boolean fresh(){return call.isActive()&&frameMatches(frame,her.getEyePosition(),her.level().dimension().location().toString());}
        String limit(){
            if(!call.isActive())return "cancelled";
            if(!her.isAlive())return "body_unavailable";
            if(!dimension.equals(her.level().dimension().location().toString()))return "dimension_changed";
            if(elapsed()>=options.maxDuration())return "time_budget";
            if(remaining()<4)return "distance_budget";
            if(her.isOnFire()||her.getHealth()<health-2)return "danger";
            return null;
        }
        void watch(){
            if(done)return;
            long now=System.nanoTime();
            if(dimension.equals(her.level().dimension().location().toString()))travel.sample(her.position(),(now-lastSample)/1e9);
            lastSample=now;
            String why=limit();
            if(why==null&&movement!=null&&(now-moveStarted)/1e9>6&&travel.stillSeconds()>6)why="no_progress";
            if(why!=null){
                if(movement!=null){stopReason=why;movement.stop(TaskRecord.StopCause.COMMAND);}
                else finish(why);
            }
            if(!done)later(50,this::watch);
        }
        void later(long ms,Runnable next){CompletableFuture.delayedExecutor(ms,TimeUnit.MILLISECONDS).execute(()->world.execute(()->{if(!done)next.run();}));}
        void observe(){
            if(done)return;String why=limit();if(why!=null){finish(why);return;}
            if(attempts>=options.maxWaypoints()){finish("waypoint_budget");return;}
            phaseStarted=System.nanoTime();observations++;step=new JsonObject();step.addProperty("observation",observations);
            step.add("initial_position",pos(her.position()));step.addProperty("dimension",dimension);step.addProperty("body_yaw",her.getYRot());
            SceneCamera.capture(world,her.getOwnerUuid(),her.getUUID(),cancel).whenComplete((view,error)->world.execute(()->{
                if(done)return;if(error!=null||view==null||view.eye()==null){finish("camera_unavailable");return;}
                frame=view;step.add("captured_eye",pos(frame.eye()));step.addProperty("captured_yaw",frame.yaw());
                step.addProperty("capture_elapsed_seconds",elapsed());long t=System.nanoTime();
                CompletableFuture.supplyAsync(()->infer(view,options.goal())).whenComplete((values,failure)->world.execute(()->{
                    if(done)return;
                    if(failure!=null){step.addProperty("problem",failure.toString());history.add(step);finish("inference_failed");return;}
                    step.addProperty("inference_seconds",(System.nanoTime()-t)/1e9);
                    if(!fresh()){history.add(step);finish("stale_frame");return;}
                    if(targets.isEmpty()){hits=List.of();generate(values);return;}
                    searchId=BlockSearch.start(her.getUUID(),her.serverLevel(),her.blockPosition(),32,16,targets,scan->{
                        searchId=-1;if(done)return;
                        hits=scan.matches();step.addProperty("native_hits",hits.size());step.addProperty("native_columns_unloaded",scan.columnsUnloaded());
                        step.addProperty("native_scan_complete",scan.coveredEverything());
                        if(!fresh()){history.add(step);finish("stale_frame");return;}
                        generate(values);
                    });
                }));
            }));
        }
        double visualRank(double yaw,JsonArray directions){
            double rank=0;
            for(JsonElement e:directions){var v=e.getAsJsonObject();double alignment=Math.max(0,1-ExplorationPolicy.angle(yaw,frame.yaw()+v.get("yaw_offset").getAsDouble())/90);
                rank=Math.max(rank,alignment*(.8*v.get("score").getAsDouble()+.2*v.get("exploration_score").getAsDouble()));}
            return rank;
        }
        void generate(JsonArray directions){
            if(done)return;candidates=new JsonArray();index=0;
            // 原生坐标验证目标类型,视觉给相关方向排序;不将图像航点冒充资源的精确位置。
            hits=hits.stream().filter(h->her.level().hasChunkAt(h.pos())&&targets.contains(her.level().getBlockState(h.pos()).getBlock()))
                .sorted(Comparator.comparingDouble((BlockScanner.Hit h)->visualRank(ExplorationPolicy.yaw(her.position(),Vec3.atCenterOf(h.pos())),directions)).reversed()).toList();
            if(!hits.isEmpty()&&!options.mode().equals("frontier_exploration")){
                goalFound=true;resource=hits.getFirst().pos();
                if(options.mode().equals("locate")){step.add("goal_position",pos(Vec3.atCenterOf(resource)));history.add(step);finish("goal_found");return;}
                if(her.position().distanceTo(Vec3.atCenterOf(resource))<=3.5){goalReached=true;history.add(step);finish("goal_reached");return;}
                for(BlockScanner.Hit hit:hits){
                    if(candidates.size()>=3)break;
                    Vec3 p=Vec3.atCenterOf(hit.pos());if(ExplorationPolicy.revisited(p,stalledTargets))continue;
                    JsonObject c=candidate(p,ExplorationPolicy.yaw(her.position(),p),visualRank(ExplorationPolicy.yaw(her.position(),p),directions),"native_target");
                    c.add("resource_position",pos(p));candidates.add(c);
                }
            }
            List<JsonObject> projected=new ArrayList<>();
            double far=Math.min(32,remaining()-1);
            for(JsonElement e:directions){
                JsonObject d=e.getAsJsonObject();double offset=d.get("yaw_offset").getAsDouble(),score=d.get("score").getAsDouble();
                if(!Double.isFinite(offset)||Math.abs(offset)>55||!Double.isFinite(score)||score<0||score>1)continue;
                double yaw=frame.yaw()+offset;
                double novelty=headings.isEmpty()?1:headings.stream().mapToDouble(h->Math.min(1,ExplorationPolicy.angle(h,yaw)/65)).min().orElse(1);
                for(double distance:new double[]{far,far*.66,far*.4}){
                    if(distance<3)continue;Vec3 p=ExplorationPolicy.project(her.position(),yaw,distance);
                    if(ExplorationPolicy.revisited(p,visited.subList(0,Math.max(0,visited.size()-1)))||ExplorationPolicy.revisited(p,stalledTargets))continue;
                    var c=candidate(p,yaw,score,"visual_frontier");c.addProperty("visual_exploration_score",d.get("exploration_score").getAsDouble());
                    c.addProperty("rank",5*score+d.get("exploration_score").getAsDouble()+novelty+distance/32);
                    projected.add(c);
                }
            }
            projected.sort(Comparator.comparingDouble((JsonObject c)->c.get("rank").getAsDouble()).reversed());projected.forEach(candidates::add);
            log("candidates",candidates);next();
        }
        JsonObject candidate(Vec3 p,double yaw,double confidence,String source){
            var c=new JsonObject();c.add("world_position",pos(p));c.addProperty("world_yaw",yaw);c.addProperty("score",confidence);
            c.addProperty("estimated_distance",her.position().distanceTo(p));c.addProperty("source",source);c.addProperty("reachability","pending_native_plan");return c;
        }
        void next(){
            if(done)return;String why=limit();if(why!=null){finish(why);return;}
            if(!fresh()){history.add(step);finish("stale_frame");return;}
            if((System.nanoTime()-phaseStarted)/1e9>15){rescan("perception_planning_budget");return;}
            if(index>=candidates.size()){
                rescan(candidates.isEmpty()?"no_candidates":"no_executable_candidate");return;
            }
            var c=candidates.get(index).getAsJsonObject();var p=c.getAsJsonObject("world_position");target=block(p);
            if(!her.level().getWorldBorder().isWithinBounds(target)||her.serverLevel().getChunkSource().getChunkNow(target.getX()>>4,target.getZ()>>4)==null||her.level().isOutsideBuildHeight(target)){
                c.addProperty("reachability","invalid_or_unloaded");index++;next();return;
            }
            var goal=c.get("source").getAsString().equals("native_target")?Goals.within(Goals.at(target),1,3):Goals.within(Goals.column(target.getX(),target.getZ()),0,1);
            survey=Survey.of(her,List.of(new Survey.Leg(goal,Walking.spec(her))),List.of());planStarted=System.nanoTime();poll();
        }
        void poll(){
            if(done)return;if(!fresh()){survey.cancel();finish("stale_frame");return;}
            var found=survey.poll();
            if(found==null){
                if((System.nanoTime()-planStarted)/1e9>2){survey.cancel();reject("planning_budget");}else later(50,this::poll);
                return;
            }
            var route=found.getFirst();var c=candidates.get(index).getAsJsonObject();
            c.addProperty("planning_seconds",(System.nanoTime()-planStarted)/1e9);
            if(!route.reached()){c.addProperty("planning_outcome",String.valueOf(route.outcome()));reject("not_proven_reachable");return;}
            double length=ExplorationPolicy.routeLength(route.route().nodes());
            c.addProperty("planned_path_length",length);c.addProperty("planned_nodes",route.route().nodes().size());c.addProperty("path_cost",route.route().cost());
            if(!Double.isFinite(route.route().cost())||route.route().cost()>400){reject("cost_budget");return;}
            if(length+1>remaining()){reject("distance_budget");return;}
            if(length<1){reject("no_progress_target");return;}
            c.addProperty("reachability","native_route_found");target=route.route().end();
            if(c.has("resource_position"))resource=block(c.getAsJsonObject("resource_position"));
            step.add("target_position",pos(Vec3.atBottomCenterOf(target)));step.addProperty("target_direction",c.get("world_yaw").getAsDouble());
            step.addProperty("path_cost",route.route().cost());step.addProperty("planned_path_length",length);step.addProperty("planned_nodes",route.route().nodes().size());
            step.addProperty("candidate_distance",c.get("estimated_distance").getAsDouble());step.addProperty("reachability","native_route_found");
            step.addProperty("execution_plan","numen.move.to({x="+target.getX()+",y="+target.getY()+",z="+target.getZ()+"})");
            step.addProperty("execution_arrival","at_native_route_end");
            step.add("movement_start",pos(her.position()));step.addProperty("state","EXPLORATION");log("selected",step);
            plannedTotal+=length;attempts++;moveDistance=travel.length();moveStarted=System.nanoTime();
            movement=new MoveToTaskRecord(call.id(),her.level().getGameTime(),Arrival.to(new Where.Cell(target,null),null));
            TaskDispatch.runSync(her,movement,receipt->{
                if(done)return;if(dimension.equals(her.level().dimension().location().toString()))travel.sample(her.position(),0);lastSample=System.nanoTime();
                var finished=movement;movement=null;boolean stalled="no_progress".equals(stopReason);
                if(stalled){stopReason=null;step.addProperty("local_no_progress",true);}
                double progress=travel.length()-moveDistance;step.addProperty("actual_travel_distance",progress);step.add("current_position",pos(her.position()));
                step.addProperty("execution_result",receipt.message());step.addProperty("execution_success",receipt.success());step.addProperty("elapsed_seconds",elapsed());history.add(step);log("waypoint_end",step);
                if(stopReason!=null){finish(stopReason);return;}
                if(!call.isActive()||!stalled&&finished.getStopCause()!=null){finish("cancelled");return;}
                if(receipt.success()&&progress>=1){completed++;failures=0;}
                else {stalledTargets.add(Vec3.atBottomCenterOf(target));if(++failures>=2){finish("no_progress");return;}}
                visited.add(her.position());headings.add(c.get("world_yaw").getAsDouble());
                if(resource!=null&&!options.mode().equals("frontier_exploration")&&targets.contains(her.level().getBlockState(resource).getBlock())&&her.position().distanceTo(Vec3.atCenterOf(resource))<=3.5){goalReached=true;finish("goal_reached");return;}
                turns=0;
                // 移动回执到下一轮之间只等身体收尾,不回 LLM、不更改 EXPLORATION。
                later(150,this::observe);
            });
        }
        void reject(String why){candidates.get(index).getAsJsonObject().addProperty("reachability",why);index++;next();}
        void rescan(String why){
            step.addProperty("result",why);history.add(step);
            // 当前视野受阻只换视角重看,最多三次;不从未拍摄的随机方向发动移动。
            if(turns++<3)turn();else finish(why);
        }
        void turn(){
            double yaw=her.getYRot()+90;Vec3 gaze=ExplorationPolicy.project(her.getEyePosition(),yaw,8).add(0,-2,0);
            her.lookAt(EntityAnchorArgument.Anchor.EYES,gaze);log("reorient",pos(gaze));later(200,this::observe);
        }
        void log(String event,JsonElement value){com.dwinovo.numen.Constants.LOG.info("[numen-explore] {} {}",event,value);}
        void finish(String status){
            if(done)return;done=true;cancel.cancel();if(survey!=null)survey.cancel();if(searchId!=-1)BlockSearch.cancel(searchId);
            ACTIVE.remove(her.getUUID());MODES.put(her.getUUID(),"REPLANNING");
            JsonObject result=summary(status,"REPLANNING",her.position());
            result.addProperty("success",goalReached||status.equals("goal_found")||completed>0&&Set.of("distance_budget","time_budget","waypoint_budget").contains(status));
            result.addProperty("goal",options.goal());result.addProperty("mode",options.mode());result.addProperty("goal_found",goalFound);result.addProperty("goal_reached",goalReached);
            result.add("goal_position",resource==null?JsonNull.INSTANCE:pos(Vec3.atCenterOf(resource)));result.add("initial_position",pos(initial));result.addProperty("initial_dimension",dimension);
            result.addProperty("dimension",her.level().dimension().location().toString());
            result.addProperty("total_travel_distance",travel.length());result.addProperty("net_displacement",initial.distanceTo(her.position()));
            result.addProperty("total_planned_path_length",plannedTotal);result.addProperty("waypoints_completed",completed);result.addProperty("movement_attempts",attempts);result.addProperty("observations",observations);
            result.addProperty("max_distance",options.maxDistance());result.addProperty("max_duration",options.maxDuration());result.addProperty("max_waypoints",options.maxWaypoints());
            result.addProperty("elapsed_seconds",elapsed());result.addProperty("termination_reason",status);result.add("waypoint_history",history);result.add("candidate_candidates",candidates);
            if(history.size()>0){JsonObject last=history.get(history.size()-1).getAsJsonObject();for(String key:List.of("target_position","target_direction","path_cost","execution_plan","execution_result","reachability"))if(last.has(key))result.add(key,last.get(key));}
            log("session_end",result);
            if(!call.isActive()&&attempts>0)com.dwinovo.numen.NumenContent.api().emit(her,"exploration_result",Map.of(),result.toString(),false);
            else call.complete(result.toString());
        }
    }
    static Set<Block> targetBlocks(String goal){
        Set<Block> targets=new HashSet<>();
        if(goal.equals("water"))targets.add(Blocks.WATER);
        for(Block b:BuiltInRegistries.BLOCK){var state=b.defaultBlockState();boolean match=switch(goal){
            case "wood"->state.is(BlockTags.LOGS);
            case "leaves"->state.is(BlockTags.LEAVES);
            case "ore"->state.is(BlockTags.COAL_ORES)||state.is(BlockTags.IRON_ORES)||state.is(BlockTags.COPPER_ORES)||state.is(BlockTags.GOLD_ORES)||state.is(BlockTags.DIAMOND_ORES)||state.is(BlockTags.REDSTONE_ORES)||state.is(BlockTags.LAPIS_ORES)||state.is(BlockTags.EMERALD_ORES);
            case "constructed"->state.is(BlockTags.PLANKS)||b==Blocks.BRICKS||b==Blocks.STONE_BRICKS;
            default->false;};if(match)targets.add(b);}
        return targets;
    }
    static JsonObject summary(String status,String mode,Vec3 position){
        JsonObject out=new JsonObject();out.addProperty("success",Set.of("goal_found","goal_reached","ended").contains(status));out.addProperty("exploration_status",status);out.addProperty("next_state",mode);
        out.add("current_position",position==null?JsonNull.INSTANCE:pos(position));for(String key:List.of("target_position","target_direction","path_cost","execution_plan"))out.add(key,JsonNull.INSTANCE);
        out.add("candidate_candidates",new JsonArray());out.addProperty("reachability","not_executed");out.addProperty("execution_result","no movement started");out.addProperty("coordinate_system","world_xyz; yaw_degrees");return out;
    }
    static JsonObject pos(Vec3 p){JsonObject j=new JsonObject();j.addProperty("x",p.x);j.addProperty("y",p.y);j.addProperty("z",p.z);return j;}
    static BlockPos block(JsonObject p){return BlockPos.containing(p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble());}
    static boolean frameMatches(SceneCamera.View frame,Vec3 eye,String dimension){return frame!=null&&frame.eye()!=null&&dimension.equals(frame.dimension())&&eye.distanceToSqr(frame.eye())<=.25;}

    private static JsonArray infer(SceneCamera.View frame,String goal){
        Path script=null;Process process=null;
        try {
            Properties config=new Properties();try(var reader=Files.newBufferedReader(Path.of("config/numen/exploration.properties"))){config.load(reader);}
            String python=Objects.requireNonNull(config.getProperty("python"),"exploration python missing");
            Path model=Path.of(Objects.requireNonNull(config.getProperty("model"),"exploration model missing"));
            if(!Files.isRegularFile(model))throw new IllegalStateException("exploration model not found");
            script=Files.createTempFile("numen-explore-",".py");
            try(var resource=ExploreTool.class.getResourceAsStream("/numen/explore_predict.py")){if(resource==null)throw new IllegalStateException("bundled predictor missing");Files.copy(resource,script,StandardCopyOption.REPLACE_EXISTING);}
            process=new ProcessBuilder(python,script.toString(),model.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            JsonObject request=new JsonObject();request.addProperty("goal",goal);request.addProperty("session",true);request.addProperty("png",frame.picture().pngDataUrl().substring("data:image/png;base64,".length()));
            try(var writer=process.outputWriter()){writer.write(request.toString());}
            if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("visual inference timeout");}
            if(process.exitValue()!=0)throw new IllegalStateException("visual inference failed; see local log");
            return JsonParser.parseString(new String(process.getInputStream().readNBytes(16384),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        } catch(Exception error){throw new CompletionException(error);}
        finally {if(process!=null&&process.isAlive())process.destroyForcibly();if(script!=null)try{Files.deleteIfExists(script);}catch(java.io.IOException ignored){script.toFile().deleteOnExit();}}
    }
}
