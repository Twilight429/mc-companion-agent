package com.dwinovo.numen.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

/** Explicit lossy export, not a weaker admission gate. Missing cells are omissions, never world-clearing air. */
public final class ArchitectureCompatibility {
    private ArchitectureCompatibility() {}
    public record Result(CompoundTag structure,int removed,int remaining,Map<String,Integer> reasons) {}
    public static Result removeIncompatible(CompoundTag source) {
        int version=source.getInt("DataVersion");
        if(version<=0||version>SharedConstants.getCurrentVersion().getDataVersion().getVersion())
            throw new IllegalArgumentException("未知/高版本图纸不能靠删方块伪装成当前版本");
        // The same parser validates bounds, palette indices and budgets before any edit.
        ArchitectureCatalog.inspect("compatibility_export",source,new JsonObject(),"local");
        ListTag palette=source.contains("palettes",Tag.TAG_LIST)?source.getList("palettes",Tag.TAG_LIST).getList(0):source.getList("palette",Tag.TAG_COMPOUND);
        var finalCells=new LinkedHashMap<BlockPos,CompoundTag>();
        for(Tag entry:source.getList("blocks",Tag.TAG_COMPOUND)) {
            var cell=(CompoundTag)entry; var pos=cell.getList("pos",Tag.TAG_INT);
            finalCells.put(new BlockPos(pos.getInt(0),pos.getInt(1),pos.getInt(2)),cell);
        }
        ListTag validPalette=new ListTag(),blocks=new ListTag(),removedCells=new ListTag();
        Map<Integer,Integer> remap=new HashMap<>(); Map<String,Integer> reasons=new TreeMap<>();
        for(var cell:finalCells.values()) {
            int old=cell.getInt("state"); var state=palette.getCompound(old);
            String reason=ArchitectureCatalog.invalidState(state);
            if(reason!=null) {
                reasons.merge(reason,1,Integer::sum);
                var removed=cell.copy(); removed.put("original_state",state.copy()); removed.putString("reason",reason); removedCells.add(removed);
                continue;
            }
            if(!remap.containsKey(old)) { remap.put(old,validPalette.size()); validPalette.add(state.copy()); }
            var kept=cell.copy(); kept.putInt("state",remap.get(old)); blocks.add(kept);
        }
        if(removedCells.isEmpty()) throw new IllegalArgumentException("没有不兼容方块，无需生成删减副本");
        CompoundTag result=source.copy(); result.remove("palettes"); result.put("palette",validPalette); result.put("blocks",blocks);
        var metadata=result.getCompound("Metadata").copy();
        metadata.putInt("OriginalTotalBlocks",source.getCompound("Metadata").getInt("TotalBlocks"));
        metadata.putInt("RemovedIncompatibleCells",removedCells.size());
        metadata.put("RemovedCellDetails",removedCells);
        metadata.putString("CompatibilityEdit","User-requested local derivative: incompatible cells omitted, holes are not repaired or cleared in the world. Original file unchanged.");
        result.put("Metadata",metadata);
        var checked=ArchitectureCatalog.inspect("compatibility_export",result,new JsonObject(),"local");
        if(!checked.compatible()||checked.cells()==0) throw new IllegalArgumentException("删减后仍未通过检查或已没有可用建筑方块");
        metadata.putInt("TotalBlocks",checked.cells());
        return new Result(result,removedCells.size(),checked.cells(),Map.copyOf(reasons));
    }
}
