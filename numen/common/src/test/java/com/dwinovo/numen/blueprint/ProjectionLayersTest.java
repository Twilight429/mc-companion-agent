package com.dwinovo.numen.blueprint;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionLayersTest {
    private record Cell(int y,boolean loaded,boolean matches) {}
    private OptionalInt layer(Cell... cells) { return ProjectionLayers.lowestUnfinished(List.of(cells),Cell::y,c->c.loaded()&&c.matches()); }
    @Test void startsAtTheLowestUnfinishedLayerRegardlessOfInputOrder() {
        assertEquals(-64,layer(new Cell(80,true,false),new Cell(-64,true,false),new Cell(0,true,false)).orElseThrow());
    }
    @Test void finishingTheFoundationAdvancesToTheNextUnfinishedLayer() {
        assertEquals(65,layer(new Cell(64,true,true),new Cell(65,true,false),new Cell(66,true,false)).orElseThrow());
    }
    @Test void oneMissingBlockKeepsTheWholeCurrentLayerActive() {
        assertEquals(64,layer(new Cell(64,true,true),new Cell(64,true,false),new Cell(65,true,false)).orElseThrow());
    }
    @Test void unloadedBlocksNeverCountAsCompleted() {
        assertEquals(63,layer(new Cell(63,false,true),new Cell(64,true,false)).orElseThrow());
    }
    @Test void aWrongBlockReopensALowerLayer() {
        assertEquals(63,layer(new Cell(63,true,false),new Cell(65,true,false)).orElseThrow());
    }
    @Test void noGhostsRemainWhenEverythingIsCompleteOrThePlanIsEmpty() {
        assertTrue(layer(new Cell(63,true,true),new Cell(64,true,true)).isEmpty()); assertTrue(layer().isEmpty());
    }
}
