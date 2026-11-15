package fr.citizen.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

/**
 * Plan de construction au format « citizen-plan-1 » (écrit par Claude), compilé en liste de blocs.
 *
 * {
 *   "format": "citizen-plan-1",
 *   "demande": "C-0001",
 *   "nom": "Taverne du Sanglier",
 *   "origine": [x, y, z],            coordonnées monde du point local 0,0,0
 *   "rotation": 0,                   0, 90, 180 ou 270 (sens horaire, autour de l'origine)
 *   "palette": { "P": "minecraft:spruce_planks", "E": "minecraft:spruce_stairs[facing=north]" },
 *   "operations": [
 *     {"op":"remplir",  "de":[0,0,0], "a":[9,0,9], "bloc":"P"},     pavé plein
 *     {"op":"boite",    "de":[..],    "a":[..],    "bloc":"P"},     pavé creux (6 faces)
 *     {"op":"murs",     "de":[..],    "a":[..],    "bloc":"P"},     4 murs seulement
 *     {"op":"vider",    "de":[..],    "a":[..]},                    met de l'air
 *     {"op":"bloc",     "pos":[x,y,z], "bloc":"E"},
 *     {"op":"blocs",    "bloc":"E", "pos":[[x,y,z], ...]},
 *     {"op":"ligne",    "de":[..], "a":[..], "bloc":"P"},
 *     {"op":"cylindre", "centre":[x,y,z], "rayon":4, "hauteur":6, "bloc":"P", "creux":true},
 *     {"op":"sphere",   "centre":[x,y,z], "rayon":5, "bloc":"P", "creux":false, "demi":false},
 *     {"op":"couches",  "de":[x,y,z], "couches":[ ["PPP","P.P","PPP"], ... ]}
 *        couches du bas vers le haut, lignes vers +Z, caractères vers +X ;
 *        "." ou espace = ne rien poser, "_" = air, autre caractère = clé de la palette
 *   ]
 * }
 * Un bloc peut être une clé de la palette ou directement un bloc Minecraft (avec propriétés et NBT).
 * Les portes, grandes plantes et lits sont complétés automatiquement (moitié haute / tête).
 */
public final class Plan {
    public String demande;
    public String nom = "";
    public int[] origine;
    public final List<BlockState> palette = new ArrayList<>();
    public final List<CompoundTag> paletteNbt = new ArrayList<>();
    /** Positions (BlockPos.asLong) dans l'ordre de pose, et index de palette correspondant. */
    public long[] positions;
    public int[] states;
    public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public int size() { return positions.length; }

    public static final class PlanException extends Exception {
        public PlanException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ compilation

    private final transient Map<String, Integer> specCache = new HashMap<>();
    private transient Map<String, String> paletteSpecs;
    private transient HolderLookup<Block> blocks;
    private transient LongIntMap map;
    private transient int ox, oy, oz;
    private transient Rotation rotation = Rotation.NONE;
    private transient int limit;

    public static Plan compile(String json, ServerLevel level, Demande d, int maxBlocks) throws PlanException {
        JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (Exception e) { throw new PlanException("JSON invalide : " + e.getMessage()); }
        Plan p = new Plan();
        p.limit = maxBlocks;
        p.blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        p.demande = root.has("demande") ? root.get("demande").getAsString() : (d != null ? d.id : null);
        p.nom = root.has("nom") ? root.get("nom").getAsString() : "";
        if (!root.has("origine")) throw new PlanException("« origine » manquante");
        p.origine = ints(root.get("origine"), 3, "origine");
        p.ox = p.origine[0]; p.oy = p.origine[1]; p.oz = p.origine[2];
        int rot = root.has("rotation") ? root.get("rotation").getAsInt() : 0;
        p.rotation = switch (((rot % 360) + 360) % 360) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            case 0 -> Rotation.NONE;
            default -> throw new PlanException("rotation doit valoir 0, 90, 180 ou 270");
        };
        p.paletteSpecs = new HashMap<>();
        if (root.has("palette")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("palette").entrySet())
                p.paletteSpecs.put(e.getKey(), e.getValue().getAsString());
        }
        p.map = new LongIntMap(1 << 16);
        if (!root.has("operations")) throw new PlanException("« operations » manquantes");
        JsonArray ops = root.getAsJsonArray("operations");
        for (int i = 0; i < ops.size(); i++) {
            try { p.apply(ops.get(i).getAsJsonObject()); }
            catch (PlanException e) { throw new PlanException("opération n°" + (i + 1) + " : " + e.getMessage()); }
            catch (Exception e) { throw new PlanException("opération n°" + (i + 1) + " : " + e); }
        }
        p.completeMultiBlocks();
        p.finish(level, d);
        return p;
    }

    private void apply(JsonObject o) throws PlanException {
        String op = o.has("op") ? o.get("op").getAsString() : (o.has("type") ? o.get("type").getAsString() : "");
        switch (op) {
            case "remplir", "fill" -> box(o, 0);
            case "boite", "creux", "hollow" -> box(o, 1);
            case "murs", "walls" -> box(o, 2);
            case "vider", "air", "clear" -> { o.addProperty("bloc", "minecraft:air"); box(o, 0); }
            case "bloc", "block", "set" -> { int[] a = ints(o.get("pos"), 3, "pos"); set(a[0], a[1], a[2], state(o)); }
            case "blocs", "blocks" -> {
                int s = state(o);
                for (JsonElement e : o.getAsJsonArray("pos")) { int[] a = ints(e, 3, "pos"); set(a[0], a[1], a[2], s); }
            }
            case "ligne", "line" -> line(o);
            case "cylindre", "cylinder" -> cylinder(o);
            case "sphere" -> sphere(o);
            case "couches", "layers" -> layers(o);
            default -> throw new PlanException("type d'opération inconnu « " + op + " »");
        }
    }

    private void box(JsonObject o, int mode) throws PlanException {
        int[] a = ints(o.get("de"), 3, "de"), b = ints(o.get("a"), 3, "a");
        int s = state(o);
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > limit * 2L) throw new PlanException("volume trop grand");
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1, edgeY = y == y0 || y == y1;
            if (mode == 1 && !(edgeX || edgeZ || edgeY)) continue;
            if (mode == 2 && !(edgeX || edgeZ)) continue;
            set(x, y, z, s);
        }
    }

    private void line(JsonObject o) throws PlanException {
        int[] a = ints(o.get("de"), 3, "de"), b = ints(o.get("a"), 3, "a");
        int s = state(o);
        int n = Math.max(Math.abs(b[0] - a[0]), Math.max(Math.abs(b[1] - a[1]), Math.abs(b[2] - a[2])));
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            set((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t), (int) Math.round(a[2] + (b[2] - a[2]) * t), s);
        }
    }

    private void cylinder(JsonObject o) throws PlanException {
        int[] c = ints(o.get("centre"), 3, "centre");
        double r = o.get("rayon").getAsDouble();
        int h = o.has("hauteur") ? o.get("hauteur").getAsInt() : 1;
        boolean hollow = o.has("creux") && o.get("creux").getAsBoolean();
        int s = state(o);
        int ri = (int) Math.ceil(r);
        for (int y = 0; y < h; y++) for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > r + 0.5) continue;
            if (hollow && d < r - 0.5) continue;
            set(c[0] + x, c[1] + y, c[2] + z, s);
        }
    }

    private void sphere(JsonObject o) throws PlanException {
        int[] c = ints(o.get("centre"), 3, "centre");
        double r = o.get("rayon").getAsDouble();
        boolean hollow = o.has("creux") && o.get("creux").getAsBoolean();
        boolean half = o.has("demi") && o.get("demi").getAsBoolean();
        int s = state(o);
        int ri = (int) Math.ceil(r);
        for (int y = half ? 0 : -ri; y <= ri; y++) for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
            double d = Math.sqrt(x * x + y * y + z * z);
            if (d > r + 0.5) continue;
            if (hollow && d < r - 0.5) continue;
            set(c[0] + x, c[1] + y, c[2] + z, s);
        }
    }

    private void layers(JsonObject o) throws PlanException {
        int[] a = ints(o.get("de"), 3, "de");
        JsonArray layers = o.getAsJsonArray("couches");
        for (int ly = 0; ly < layers.size(); ly++) {
            JsonArray rows = layers.get(ly).getAsJsonArray();
            for (int lz = 0; lz < rows.size(); lz++) {
                String row = rows.get(lz).getAsString();
                for (int lx = 0; lx < row.length(); lx++) {
                    char ch = row.charAt(lx);
                    if (ch == '.' || ch == ' ') continue;
                    int s = ch == '_' ? spec("minecraft:air") : key(String.valueOf(ch));
                    set(a[0] + lx, a[1] + ly, a[2] + lz, s);
                }
            }
        }
    }

    private int state(JsonObject o) throws PlanException {
        if (!o.has("bloc")) throw new PlanException("« bloc » manquant");
        return key(o.get("bloc").getAsString());
    }

    private int key(String k) throws PlanException {
        String spec = paletteSpecs.get(k);
        if (spec == null) {
            if (k.length() == 1) throw new PlanException("clé de palette inconnue « " + k + " »");
            spec = k;
        }
        return spec(spec);
    }

    private int spec(String spec) throws PlanException {
        Integer cached = specCache.get(spec);
        if (cached != null) return cached;
        String s = spec.contains(":") || spec.startsWith("#") ? spec : "minecraft:" + spec;
        BlockStateParser.BlockResult r;
        try { r = BlockStateParser.parseForBlock(blocks, s, true); }
        catch (Exception e) { throw new PlanException("bloc invalide « " + spec + " » : " + e.getMessage()); }
        BlockState st = r.blockState();
        if (rotation != Rotation.NONE) st = st.rotate(rotation);
        palette.add(st);
        paletteNbt.add(r.nbt());
        int idx = palette.size() - 1;
        specCache.put(spec, idx);
        return idx;
    }

    private void set(int lx, int ly, int lz, int state) throws PlanException {
        int rx, rz;
        switch (rotation) {
            case CLOCKWISE_90 -> { rx = -lz; rz = lx; }
            case CLOCKWISE_180 -> { rx = -lx; rz = -lz; }
            case COUNTERCLOCKWISE_90 -> { rx = lz; rz = -lx; }
            default -> { rx = lx; rz = lz; }
        }
        map.put(BlockPos.asLong(ox + rx, oy + ly, oz + rz), state);
        if (map.size() > limit) throw new PlanException("plan trop grand (plus de " + limit + " blocs)");
    }

    /** Ajoute la moitié haute des portes / grandes plantes et la tête des lits. */
    private void completeMultiBlocks() {
        List<long[]> extra = new ArrayList<>();
        Map<Integer, Integer> upperCache = new HashMap<>();
        map.forEach((pos, idx) -> {
            BlockState s = palette.get(idx);
            if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                long up = BlockPos.offset(pos, Direction.UP);
                if (!map.contains(up)) extra.add(new long[]{up, upperCache.computeIfAbsent(idx, i -> addState(s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)))});
            } else if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                long head = BlockPos.offset(pos, s.getValue(BlockStateProperties.HORIZONTAL_FACING));
                if (!map.contains(head)) extra.add(new long[]{head, upperCache.computeIfAbsent(-1 - idx, i -> addState(s.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)))});
            }
        });
        for (long[] e : extra) map.put(e[0], (int) e[1]);
    }

    private int addState(BlockState s) {
        palette.add(s);
        paletteNbt.add(null);
        return palette.size() - 1;
    }

    /** Tri : l'air d'abord, puis les blocs pleins, puis le reste (torches, portes…), du bas vers le haut. */
    private void finish(ServerLevel level, Demande d) throws PlanException {
        int n = map.size();
        if (n == 0) throw new PlanException("le plan ne contient aucun bloc");
        map.forEach((pos, idx) -> {
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        });
        if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight())
            throw new PlanException("hauteur hors du monde (" + minY + " à " + maxY + ")");
        if (d != null) {
            int m = 16;
            int ax = Math.min(d.x0, d.x1) - m, bx = Math.max(d.x0, d.x1) + m, az = Math.min(d.z0, d.z1) - m, bz = Math.max(d.z0, d.z1) + m;
            if (minX < ax || maxX > bx || minZ < az || maxZ > bz)
                throw new PlanException("le plan déborde de la zone sélectionnée de plus de 16 blocs (plan : " + minX + "," + minZ + " → " + maxX + "," + maxZ + ")");
        }
        long sx = maxX - minX + 1L, sz = maxZ - minZ + 1L, sy = maxY - minY + 1L;
        long[] keys = new long[n];
        int[] k = {0};
        map.forEach((pos, idx) -> {
            BlockState s = palette.get(idx);
            long pass = s.isAir() ? 0 : s.canOcclude() ? 1 : 2;
            long x = BlockPos.getX(pos) - minX, y = BlockPos.getY(pos) - minY, z = BlockPos.getZ(pos) - minZ;
            keys[k[0]++] = ((pass * sy + y) * sx + x) * sz + z;
        });
        Arrays.sort(keys);
        positions = new long[n];
        states = new int[n];
        for (int i = 0; i < n; i++) {
            long v = keys[i];
            int z = (int) (v % sz); v /= sz;
            int x = (int) (v % sx); v /= sx;
            int y = (int) (v % sy);
            long pos = BlockPos.asLong(minX + x, minY + y, minZ + z);
            positions[i] = pos;
            states[i] = map.get(pos, 0);
        }
        map = null;
    }

    private static int[] ints(JsonElement e, int n, String name) throws PlanException {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) throw new PlanException("« " + name + " » doit être [x, y, z]");
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = e.getAsJsonArray().get(i).getAsInt();
        return r;
    }

    public static boolean isConnecting(BlockState s) {
        Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.CrossCollisionBlock
                || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.StairBlock
                || b instanceof net.minecraft.world.level.block.ChestBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b == Blocks.TRIPWIRE;
    }
}
