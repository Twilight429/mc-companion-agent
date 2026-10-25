package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.NumenApiFixture;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import java.nio.file.Path;

/** 显式传入的用户作品仅在本机审计,不复制进测试资源或发行 jar。 */
public final class ArchitectureSampleAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 2) throw new IllegalArgumentException("supply .litematic, optionally an explicit new compatible .nbt output path");
        NumenApiFixture.install();
        Path path = Path.of(args[0]);
        var raw = NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024 * 1024));
        var structure = BlueprintFormats.fromLitematic(raw);
        structure.putInt("DataVersion", raw.getInt("MinecraftDataVersion"));
        structure.put("Metadata", raw.getCompound("Metadata"));
        var entry = ArchitectureCatalog.inspect(path.getFileName().toString().replaceFirst("\\.litematic$", ""), structure,
                new JsonObject(), "local-audit-not-an-import");
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(entry));
        if (entry.cells() != raw.getCompound("Metadata").getInt("TotalBlocks"))
            throw new AssertionError("decoded block count differs from file metadata");
        System.out.println("SOURCE_BLOCK_COUNT_VERIFIED");
        if(args.length==2) {
            Path output=Path.of(args[1]).toAbsolutePath().normalize();
            if(!output.toString().endsWith(".nbt")||output.equals(path.toAbsolutePath().normalize())) throw new IllegalArgumentException("write a distinct new .nbt file");
            if(java.nio.file.Files.exists(output)) throw new IllegalArgumentException("output already exists; will not overwrite");
            var edited=ArchitectureCompatibility.removeIncompatible(structure);
            var before=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path));
            // CREATE_NEW protects an existing file even if another process creates it after the check.
            try(var file=java.nio.file.Files.newOutputStream(output,java.nio.file.StandardOpenOption.CREATE_NEW)) { NbtIo.writeCompressed(edited.structure(),file); }
            var reread=NbtIo.readCompressed(output,NbtAccounter.create(64L*1024*1024));
            var checked=ArchitectureCatalog.inspect(output.getFileName().toString(),reread,new JsonObject(),"local-export");
            if(!checked.compatible()||checked.cells()!=edited.remaining()) throw new AssertionError("written compatible copy failed readback");
            var after=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path));
            if(!java.util.Arrays.equals(before,after)) throw new AssertionError("source changed during export");
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of(
                    "output",output.toString(),"removed",edited.removed(),"remaining",edited.remaining(),"reasons",edited.reasons(),"originalSHA256",java.util.HexFormat.of().formatHex(after),"readbackCompatible",checked.compatible())));
            System.out.println("COMPATIBLE_COPY_READBACK_VERIFIED_SOURCE_UNCHANGED");
        }
    }
}
