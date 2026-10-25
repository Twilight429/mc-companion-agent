package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import net.minecraft.world.InteractionHand;

/** No attack packets, health edits, forced player movement or second body controller. */
public final class SocialTask extends AbstractCompanionTask<SocialTaskRecord,String> {
    private float yaw,pitch,lastYaw,turned;
    private double y;
    private boolean airborne,swung;
    public SocialTask(NumenPlayer body,SocialTaskRecord record) { super(body,record); }
    @Override protected void onStart() {
        yaw=lastYaw=player.getYRot(); pitch=player.getXRot(); y=player.getY();
        player.controls().stop();
        if(!player.onGround()||player.isInWater()||player.isInLava()||player.getHealth()<10)
            fail("现在不适合庆祝，先站稳并保证安全；没有执行互动",FailureType.HAZARD);
    }
    @Override protected TaskState onTick() {
        var owner=player.getServer().getPlayerList().getPlayer(player.getOwnerUuid());
        if(owner==null||!owner.isAlive()||owner.serverLevel()!=player.serverLevel()||owner.distanceToSqr(player)>16||player.getHealth()<10||player.isInWater()||player.isInLava()) {
            fail("互动中止：主人离开或当前不安全",FailureType.HAZARD); return TaskState.FAILED;
        }
        long tick=workTicks();
        switch(r.kind) {
            case "high_five" -> {
                player.look().at(owner.getEyePosition());
                if(!swung) { player.swing(InteractionHand.MAIN_HAND); swung=true; }
                if(tick>=8) return TaskState.SUCCESS;
            }
            case "jump" -> {
                if(tick<2) player.controls().press(Controls.Key.JUMP); else player.controls().release(Controls.Key.JUMP);
                if(player.getY()>y+.1) airborne=true;
                if(tick>5&&airborne&&player.onGround()) return TaskState.SUCCESS;
                if(tick>=45) { fail("未能确认完成一次跳跃，没有虚报成功",FailureType.NOT_KEPT); return TaskState.FAILED; }
            }
            case "spin" -> {
                turned+=Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot()-lastYaw)); lastYaw=player.getYRot();
                if(tick<=40) player.look().turn(yaw+tick*9,pitch);
                if(tick>=44) {
                    if(turned>=330) return TaskState.SUCCESS;
                    fail("转身幅度不足，没有虚报完成一圈",FailureType.NOT_KEPT); return TaskState.FAILED;
                }
            }
        }
        return TaskState.RUNNING;
    }
    @Override protected String value() { return r.kind; }
    @Override protected String successMessage() { return switch(r.kind) {
        case "high_five" -> "已回应击掌手势（挥手示意，无伤害，不代表模型自带击掌动画）";
        case "jump" -> "完成一次跳跃，继续一起干活吧～";
        default -> "转了一圈庆祝，继续原来的任务～";
    }; }
    @Override protected void cleanup() { player.controls().release(Controls.Key.JUMP); super.cleanup(); }
}
