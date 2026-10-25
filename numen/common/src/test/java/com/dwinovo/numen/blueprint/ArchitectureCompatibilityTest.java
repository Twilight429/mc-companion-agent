package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.NumenApiFixture;
import com.google.gson.JsonObject;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureCompatibilityTest {
    @BeforeAll static void boot() { NumenApiFixture.install(); }
    private CompoundTag source() throws Exception {
        return TagParser.parseTag("{DataVersion:3955,size:[3,2,1],Metadata:{Author:'Original author',TotalBlocks:3},palette:[{Name:'minecraft:stone'},{Name:'missing:mod_block'},{Name:'minecraft:oak_stairs',Properties:{facing:'up'}}],blocks:[{pos:[0,0,0],state:0},{pos:[1,0,0],state:1},{pos:[2,0,0],state:2}]}");
    }
    @Test void removesUnknownAndInvalidStatesWithoutTouchingOriginalOrInventingAir() throws Exception {
        var original=source(); var saved=original.copy(); var copy=ArchitectureCompatibility.removeIncompatible(original);
        assertEquals(saved,original); assertEquals(2,copy.removed()); assertEquals(1,copy.remaining());
        assertEquals(1,copy.structure().getList("palette",Tag.TAG_COMPOUND).size());
        assertEquals(1,copy.structure().getList("blocks",Tag.TAG_COMPOUND).size());
        assertTrue(ArchitectureCatalog.inspect("copy",copy.structure(),new JsonObject(),"hash").compatible());
    }
    @Test void preservesAuthorshipAndExactAuditOfHoles() throws Exception {
        var copy=ArchitectureCompatibility.removeIncompatible(source()); var meta=copy.structure().getCompound("Metadata");
        assertEquals("Original author",meta.getString("Author")); assertEquals(2,meta.getList("RemovedCellDetails",Tag.TAG_COMPOUND).size());
        assertEquals(1,meta.getInt("TotalBlocks")); assertEquals(3,meta.getInt("OriginalTotalBlocks"));
        assertTrue(meta.getString("CompatibilityEdit").contains("holes"));
    }
    @Test void neverRelabelsFutureOrMissingDataVersions() throws Exception {
        for(int version:new int[]{0,999999}) { var source=source(); source.putInt("DataVersion",version); assertThrows(IllegalArgumentException.class,()->ArchitectureCompatibility.removeIncompatible(source)); }
    }
    @Test void rejectsCorruptionRatherThanErasingIt() throws Exception {
        var source=source(); source.getList("blocks",Tag.TAG_COMPOUND).getCompound(1).putInt("state",1000);
        assertThrows(IllegalArgumentException.class,()->ArchitectureCompatibility.removeIncompatible(source));
    }
    @Test void doesNotResurrectAnEarlierOverwrittenCell() throws Exception {
        var source=source(); var later=source.getList("blocks",Tag.TAG_COMPOUND).getCompound(1).copy();
        later.put("pos",source.getList("blocks",Tag.TAG_COMPOUND).getCompound(0).get("pos").copy());
        source.getList("blocks",Tag.TAG_COMPOUND).add(later);
        assertThrows(IllegalArgumentException.class,()->ArchitectureCompatibility.removeIncompatible(source));
    }
}
