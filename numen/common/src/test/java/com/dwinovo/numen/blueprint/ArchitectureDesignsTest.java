package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.build.Design;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureDesignsTest {
    private static final UUID OWNER = UUID.randomUUID();
    @BeforeAll static void boot() { NumenApiFixture.install(); }
    private static Design design(Design.Cell... cells) { return new Design(List.of(cells), null); }
    private static Design.Cell cell(int x,int y,int z,String block) { return new Design.Cell(new BlockPos(x,y,z),block); }
    private static net.minecraft.nbt.CompoundTag encode(Design plan) { return ArchitectureDesigns.structure(plan,OWNER,"medieval_rustic"); }
    @Test void serializesLocalStatesWithRealVersionAndOwnedProvenance() {
        var tag=encode(design(cell(0,0,0,"oak_planks"),cell(2,1,3,"oak_stairs[facing=east,half=top]")));
        assertEquals(2,tag.getList("blocks",Tag.TAG_COMPOUND).size());
        assertTrue(tag.getInt("DataVersion")>0); assertTrue(ArchitectureDesigns.owned(tag,OWNER));
        assertFalse(ArchitectureDesigns.owned(tag,UUID.randomUUID()));
        var entry=ArchitectureCatalog.inspect("original",tag,new JsonObject(),"hash");
        assertTrue(entry.compatible());assertEquals(List.of(3,2,4),entry.size());
        assertTrue(ArchitectureDesigns.read(tag).get(1).block().contains("facing=east"));
    }
    @Test void rejectsUnknownStyleOrPathInsteadOfClaimingItWasLearned() {
        for(String style:List.of("made_up","../chinese_classical","decoration"))
            assertThrows(IllegalArgumentException.class,()->ArchitectureDesigns.style(style));
        for(String style:List.of("medieval_rustic","modern_minimalist","chinese_classical","ruins_overgrown"))
            assertEquals(style,ArchitectureDesigns.style(style));
    }
    @Test void invalidStatesItemsAndLiquidsAreRefusedNotSilentlyDropped() {
        for(String block:List.of("missing:block","diamond_sword","oak_stairs[facing=up]","water","lava"))
            assertThrows(IllegalArgumentException.class,()->encode(design(cell(0,0,0,block))));
    }
    @Test void coordinatesAreBoundedLocalCellsNotWorldCoordinates() {
        for(var cell:List.of(cell(-1,0,0,"stone"),cell(0,256,0,"stone"),cell(0,0,1000,"stone")))
            assertThrows(IllegalArgumentException.class,()->encode(design(cell)));
    }
    @Test void emptyBlueprintOnlyAirAndOversizedDesignsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->encode(design()));
        assertThrows(IllegalArgumentException.class,()->encode(design(cell(0,0,0,"air"))));
        assertThrows(IllegalArgumentException.class,()->encode(new Design(Collections.nCopies(32769,cell(0,0,0,"stone")),null)));
        assertThrows(IllegalArgumentException.class,()->encode(new Design(null,new Design.Blueprint("existing",BlockPos.ZERO,0))));
    }
    @Test void duplicateCellsMustBeResolvedExplicitlyByShapeUnion() {
        assertThrows(IllegalArgumentException.class,()->encode(design(cell(0,0,0,"stone"),cell(0,0,0,"glass"))));
    }
    @Test void normalizesTheSameRuntimeStatesAsTheNativeBuilder() {
        var cells=ArchitectureDesigns.read(encode(design(cell(0,0,0,"oak_leaves"),cell(1,0,0,"oak_slab[waterlogged=true]"))));
        assertTrue(cells.get(0).block().contains("persistent=true"));assertTrue(cells.get(1).block().contains("waterlogged=false"));
    }
    @Test void doorAndBedProjectionIncludesBothHalvesWithoutDuplicatingMaterials() {
        var tag=encode(design(cell(0,0,0,"oak_door[facing=north]"),cell(2,0,2,"red_bed[facing=south]")));
        var cells=ArchitectureDesigns.read(tag);
        assertEquals(4,cells.size());
        assertTrue(cells.stream().anyMatch(c->c.pos().equals(new BlockPos(0,1,0))&&c.block().contains("half=upper")));
        assertTrue(cells.stream().anyMatch(c->c.pos().equals(new BlockPos(2,0,3))&&c.block().contains("part=head")));
    }
    @Test void orphanConflictingOrOutOfBoundsSecondHalvesAreRefused() {
        for(var plan:List.of(design(cell(0,1,0,"oak_door[half=upper]")),
                design(cell(0,0,0,"oak_door"),cell(0,1,0,"stone")),design(cell(0,255,0,"oak_door"))))
            assertThrows(IllegalArgumentException.class,()->encode(plan));
    }
    @Test void patchDoesNotMutatePreviousDraftAndKeepsExplicitAirDistinctFromRemoval() {
        var source=ArchitectureDesigns.read(encode(design(cell(0,0,0,"stone"),cell(1,0,0,"stone"))));
        var changed=ArchitectureDesigns.patch(source,Optional.of(design(cell(2,0,0,"air"))),List.of(new BlockPos(1,0,0)));
        assertEquals(2,source.size()); assertEquals(2,changed.cells().size());
        assertTrue(changed.cells().stream().anyMatch(c->c.pos().equals(new BlockPos(2,0,0))&&c.block().equals("air")));
        assertTrue(ArchitectureCatalog.inspect("changed",encode(changed),new JsonObject(),"hash").compatible());
    }
    @Test void removingOrReplacingDoorRemovesItsOldSecondHalfOnly() {
        var source=ArchitectureDesigns.read(encode(design(cell(0,0,0,"oak_door"),cell(2,0,0,"stone"))));
        var remove=ArchitectureDesigns.patch(source,Optional.empty(),List.of(new BlockPos(0,0,0)));
        assertEquals(1,remove.cells().size());
        var replace=ArchitectureDesigns.patch(source,Optional.of(design(cell(0,0,0,"glass"))),List.of());
        assertEquals(2,replace.cells().size());assertEquals(2,ArchitectureDesigns.read(encode(replace)).size());
    }
    @Test void metadataAndNoOpPatchesAreBounded() {
        assertThrows(IllegalArgumentException.class,()->ArchitectureDesigns.text(" ","title",160));
        assertThrows(IllegalArgumentException.class,()->ArchitectureDesigns.text("x".repeat(161),"title",160));
        assertThrows(IllegalArgumentException.class,()->ArchitectureDesigns.patch(List.of(),Optional.empty(),List.of()));
    }
}
