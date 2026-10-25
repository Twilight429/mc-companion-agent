package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedBlocksTest {
    @Test void recordsAreIdempotentAndSurviveSaveAndReload() {
        UUID owner=UUID.randomUUID();
        var cell=new BlockPos(15,64,-1);var other=new BlockPos(16,65,-17);
        var data=new ProtectedBlocks();data.protect(owner,List.of(cell,other,cell));
        var restored=ProtectedBlocks.load(data.save(new CompoundTag(),null),null);
        assertTrue(restored.contains(owner,cell));assertTrue(restored.contains(owner,other));
        assertFalse(restored.contains(cell.above()));assertFalse(restored.isDirty());
    }
    @Test void releasingOneOwnerDoesNotRemoveAnotherOwnersOverlappingProtection() {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        var data=new ProtectedBlocks();var cell=new BlockPos(1,64,1);
        data.protect(owner,List.of(cell));data.protect(other,List.of(cell));
        data.release(UUID.randomUUID(),List.of(cell));assertTrue(data.contains(cell));
        data.release(owner,List.of(cell));assertFalse(data.contains(owner,cell));assertTrue(data.contains(other,cell));
        data.release(other,List.of(cell));assertFalse(data.contains(cell));
    }
    @Test void aReleaseOutsideTheChosenFootprintDoesNotAffectOtherBuildings() {
        UUID owner=UUID.randomUUID();var data=new ProtectedBlocks();
        var a=new BlockPos(0,64,0);var b=new BlockPos(20,64,20);
        data.protect(owner,List.of(a,b));data.release(owner,List.of(a));
        assertFalse(data.contains(a));assertTrue(data.contains(b));
    }
}
