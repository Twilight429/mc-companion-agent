package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskRecord;

public final class SocialTaskRecord extends TaskRecord<String> {
    public final String kind;
    public SocialTaskRecord(NumenPlayer body,String kind) {
        super("numen.social.respond","",body.level().getGameTime()+100);
        this.kind=SocialInteraction.kind(kind);
    }
    @Override public String describe() { return "consented social gesture: "+kind; }
}
