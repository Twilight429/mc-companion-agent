package com.dwinovo.numen.blueprint;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Consumer;

/** Catalog inspection is not a placement proposal; it must never replace the owner's world draft. */
public record ArchitectureThumbnailPayload(ArchitectureViewPayload view) implements CustomPacketPayload {
    public static final Type<ArchitectureThumbnailPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("numen","architecture_thumbnail"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArchitectureThumbnailPayload> CODEC=StreamCodec.of(
            (b,p)->ArchitectureViewPayload.STREAM_CODEC.encode(b,p.view()),
            b->new ArchitectureThumbnailPayload(ArchitectureViewPayload.STREAM_CODEC.decode(b)));
    public static volatile Consumer<ArchitectureThumbnailPayload> onClient=ignored->{};
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
