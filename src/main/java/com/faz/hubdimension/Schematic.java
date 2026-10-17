package com.faz.hubdimension;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Sponge schematics (WorldEdit formats sponge.1 / sponge.2 / sponge.3, file ending .schem).
 * Block entities (command blocks, Create machines, chests...) are kept, entities are ignored.
 */
public final class Schematic {
    public final int width, height, length;
    private final BlockState[] palette;
    private final int[] indices; // index = x + z*width + y*width*length
    /** key = BlockPos.asLong(x,y,z) relative to the schematic corner */
    public final Map<Long, CompoundTag> blockEntities;
    /** entities, positions relative to the schematic corner */
    public final List<CompoundTag> entities;

    private Schematic(int w, int h, int l, BlockState[] palette, int[] indices, Map<Long, CompoundTag> bes,
                      List<CompoundTag> entities) {
        this.entities = entities;
        this.width = w;
        this.height = h;
        this.length = l;
        this.palette = palette;
        this.indices = indices;
        this.blockEntities = bes;
    }

    public int volume() {
        return indices.length;
    }

    public BlockState stateAt(int i) {
        int p = indices[i];
        return p >= 0 && p < palette.length ? palette[p] : Blocks.AIR.defaultBlockState();
    }

    public static Schematic load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

        CompoundTag header;   // holds Width / Height / Length / DataVersion
        CompoundTag blocks;   // holds the palette + block data + block entities
        String dataKey;
        String beKey;
        boolean v3 = false;

        if (root.contains("Schematic", Tag.TAG_COMPOUND)) {
            // sponge.3
            header = root.getCompound("Schematic");
            blocks = header.getCompound("Blocks");
            dataKey = "Data";
            beKey = "BlockEntities";
            v3 = true;
        } else if (root.contains("Palette", Tag.TAG_COMPOUND) && root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            // sponge.1 and sponge.2
            header = root;
            blocks = root;
            dataKey = "BlockData";
            beKey = root.contains("BlockEntities", Tag.TAG_LIST) ? "BlockEntities" : "TileEntities";
        } else if (root.contains("Blocks", Tag.TAG_BYTE_ARRAY)) {
            throw new IOException("This is a legacy MCEdit .schematic (numeric block ids from Minecraft 1.12). "
                    + "It cannot contain 1.21 or modded blocks. Re-save it in WorldEdit as sponge.3.");
        } else {
            throw new IOException("Unknown schematic format (expected Sponge sponge.1/2/3).");
        }

        int width = header.getShort("Width") & 0xFFFF;
        int height = header.getShort("Height") & 0xFFFF;
        int length = header.getShort("Length") & 0xFFFF;

        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int saved = header.getInt("DataVersion");
        if (saved != 0 && saved != current) {
            HubDimension.LOGGER.warn("Schematic DataVersion is {} but the game is {}. Block entity data (chest contents etc.) "
                    + "is not converted - save the schematic from a 1.21.1 world to be safe.", saved, current);
        }

        // --- palette: "minecraft:oak_stairs[facing=north]" -> number ---
        CompoundTag paletteTag = blocks.getCompound("Palette");
        int max = 0;
        for (String key : paletteTag.getAllKeys()) {
            max = Math.max(max, paletteTag.getInt(key));
        }
        BlockState[] palette = new BlockState[max + 1];
        Arrays.fill(palette, Blocks.AIR.defaultBlockState());
        Set<String> missing = new TreeSet<>();
        for (String key : paletteTag.getAllKeys()) {
            try {
                palette[paletteTag.getInt(key)] =
                        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), key, false).blockState();
            } catch (CommandSyntaxException e) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            HubDimension.LOGGER.warn("Schematic uses {} block state(s) that do not exist here (mod missing?). They become air: {}",
                    missing.size(), missing);
        }

        // --- block data: one VarInt per block ---
        byte[] data = blocks.getByteArray(dataKey);
        int[] indices = new int[width * height * length];
        int pos = 0;
        for (int i = 0; i < indices.length && pos < data.length; i++) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            indices[i] = value;
        }

        // --- block entities ---
        Map<Long, CompoundTag> bes = new HashMap<>();
        ListTag list = blocks.getList(beKey, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int[] p = entry.getIntArray("Pos");
            if (p.length != 3) continue;
            CompoundTag tag;
            if (v3) {
                tag = entry.getCompound("Data").copy();
            } else {
                tag = entry.copy();
                tag.remove("Pos");
                tag.remove("Id");
            }
            tag.putString("id", entry.getString("Id"));
            bes.put(BlockPos.asLong(p[0], p[1], p[2]), tag);
        }

        // --- entities (armor stands, parrots, item frames...) ---
        List<CompoundTag> entities = new ArrayList<>();
        ListTag entityList = header.getList("Entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entry = entityList.getCompound(i);
            ListTag pos3 = entry.getList("Pos", Tag.TAG_DOUBLE);
            if (pos3.size() != 3) continue;
            CompoundTag tag;
            if (v3) {
                tag = entry.getCompound("Data").copy();
                if (!tag.contains("Rotation") && entry.contains("Rotation", Tag.TAG_LIST)) {
                    tag.put("Rotation", entry.get("Rotation").copy());
                }
            } else {
                tag = entry.copy();
                tag.remove("Id");
            }
            tag.putString("id", entry.getString("Id"));
            tag.put("Pos", pos3.copy());
            entities.add(tag);
        }

        return new Schematic(width, height, length, palette, indices, bes, entities);
    }
}
