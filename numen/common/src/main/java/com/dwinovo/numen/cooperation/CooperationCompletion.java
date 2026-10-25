package com.dwinovo.numen.cooperation;

/** 完成约定范围、整栋验收和装饰待核对是不同事实。 */
public final class CooperationCompletion {
    private CooperationCompletion() {}
    public static String phase(boolean stale,int scopeTargets,int scopeWrong,int scopeUnloaded,
                               boolean full,int dropped,boolean fixtures) {
        if(stale||scopeWrong>0||scopeUnloaded>0) return "active";
        if(scopeTargets==0) return "review";
        if(!full) return "completed";
        return dropped>0||fixtures?"review":"completed";
    }
}
