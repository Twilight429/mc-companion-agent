package com.dwinovo.numen.blueprint;

import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 显示层只由尚未核对完成的格子决定,不改变施工范围或权限。 */
public final class ProjectionLayers {
    private ProjectionLayers() {}
    public static <T> OptionalInt lowestUnfinished(Iterable<T> cells,ToIntFunction<T> height,Predicate<T> complete) {
        int lowest=Integer.MAX_VALUE;
        boolean found=false;
        for(T cell:cells) if(!complete.test(cell)) { lowest=Math.min(lowest,height.applyAsInt(cell)); found=true; }
        return found?OptionalInt.of(lowest):OptionalInt.empty();
    }
}
