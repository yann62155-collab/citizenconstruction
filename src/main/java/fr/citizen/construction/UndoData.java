package fr.citizen.construction;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Instantané du terrain avant une construction, pour pouvoir tout remettre comme avant. */
public final class UndoData {
    public final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> index = new HashMap<>();
    private long[] pos = new long[4096];
    private int[] st = new int[4096];
    private int size;
    public final Map<Integer, CompoundTag> blockEntities = new HashMap<>();
    private final LongIntMap seen = new LongIntMap(4096);

    public int size() { return size; }
    public long pos(int i) { return pos[i]; }
    public BlockState state(int i) { return palette.get(st[i]); }
    public boolean has(long p) { return seen.contains(p); }

    public void add(long p, BlockState s, CompoundTag be) {
        if (seen.contains(p)) return;
        if (size == pos.length) {
            pos = Arrays.copyOf(pos, size * 2);
            st = Arrays.copyOf(st, size * 2);
        }
        Integer i = index.get(s);
        if (i == null) { palette.add(s); i = palette.size() - 1; index.put(s, i); }
        pos[size] = p;
        st[size] = i;
        if (be != null) blockEntities.put(size, be);
        seen.put(p, size);
        size++;
    }

    public void save(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        ListTag pal = new ListTag();
        for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
        root.put("palette", pal);
        root.put("pos", new LongArrayTag(Arrays.copyOf(pos, size)));
        root.put("st", new IntArrayTag(Arrays.copyOf(st, size)));
        ListTag bes = new ListTag();
        for (Map.Entry<Integer, CompoundTag> e : blockEntities.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putInt("i", e.getKey());
            t.put("nbt", e.getValue());
            bes.add(t);
        }
        root.put("be", bes);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    public static UndoData load(Path file, ServerLevel level) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        UndoData u = new UndoData();
        List<BlockState> pal = new ArrayList<>();
        ListTag palTag = root.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palTag.size(); i++) pal.add(NbtUtils.readBlockState(blocks, palTag.getCompound(i)));
        long[] p = root.getLongArray("pos");
        int[] s = root.getIntArray("st");
        Map<Integer, CompoundTag> bes = new HashMap<>();
        ListTag beTag = root.getList("be", Tag.TAG_COMPOUND);
        for (int i = 0; i < beTag.size(); i++) bes.put(beTag.getCompound(i).getInt("i"), beTag.getCompound(i).getCompound("nbt"));
        for (int i = 0; i < Math.min(p.length, s.length); i++) u.add(p[i], pal.get(s[i]), bes.get(i));
        return u;
    }
}
