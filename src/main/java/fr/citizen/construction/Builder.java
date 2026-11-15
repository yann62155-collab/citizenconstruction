package fr.citizen.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pose les blocs petit à petit (X blocs par tick) directement dans le monde chargé :
 * aucun redémarrage, les joueurs voient la construction monter en direct.
 */
public final class Builder {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    public static final class Job {
        final Demande d;
        final Plan plan;        // construction
        final UndoData restore; // ou annulation
        final UndoData record;  // instantané pris pendant la construction
        int index;
        int phase;              // 0 = pose, 1 = raccords (barrières, escaliers…), 2 = fini
        int connIndex;
        int lastMark;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        Job(Demande d, Plan plan, UndoData restore, UndoData record) {
            this.d = d; this.plan = plan; this.restore = restore; this.record = record;
        }

        int total() { return plan != null ? plan.size() : restore.size(); }
        public boolean isUndo() { return restore != null; }
    }

    private final DemandeStore store;
    private final Deque<Job> queue = new ArrayDeque<>();
    private Job current;
    private long lastSave;

    public Builder(DemandeStore store) { this.store = store; }

    public synchronized Job current() { return current; }
    public synchronized int queued() { return queue.size(); }

    public synchronized boolean busyWith(String id) {
        if (current != null && current.d.id.equals(id)) return true;
        for (Job j : queue) if (j.d.id.equals(id)) return true;
        return false;
    }

    /** Lance la construction (sur le thread serveur). */
    public synchronized void startBuild(ServerLevel level, Demande d, Plan plan) throws Exception {
        UndoData rec;
        if (Files.exists(store.undoFile(d.id)) && Demande.INTERROMPUE.equals(d.statut)) {
            rec = UndoData.load(store.undoFile(d.id), level); // reprise : on garde le terrain d'origine déjà noté
        } else {
            rec = new UndoData();
        }
        Job j = new Job(d, plan, null, rec);
        d.total = plan.size();
        d.progression = 0;
        store.update(d, Demande.EN_CONSTRUCTION, current == null ? "Construction en cours…" : "En file d'attente…");
        queue.add(j);
    }

    public synchronized void startUndo(ServerLevel level, Demande d) throws Exception {
        if (!Files.exists(store.undoFile(d.id))) throw new IllegalStateException("aucun instantané du terrain pour " + d.id);
        UndoData u = UndoData.load(store.undoFile(d.id), level);
        Job j = new Job(d, null, u, null);
        d.total = u.size();
        d.progression = 0;
        store.update(d, Demande.ANNULATION, "Remise du terrain d'origine…");
        queue.add(j);
    }

    /** Arrête tout (garde l'instantané pour pouvoir annuler ou reprendre). */
    public synchronized void stopAll(String reason) {
        if (current != null) interrupt(current, reason);
        for (Job j : queue) store.update(j.d, j.d.annulable ? Demande.INTERROMPUE : Demande.PLAN_RECU, reason);
        queue.clear();
        current = null;
    }

    private void interrupt(Job j, String reason) {
        if (j.record != null && j.record.size() > 0) {
            try { j.record.save(store.undoFile(j.d.id)); j.d.annulable = true; }
            catch (Exception e) { CitizenConstruction.LOG.error("[CitizenConstruction] Instantané {} non sauvegardé", j.d.id, e); }
        }
        markMap(j);
        store.update(j.d, Demande.INTERROMPUE, reason);
    }

    public synchronized void tick(ServerLevel level) {
        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            store.update(current.d, null, current.isUndo() ? "Remise du terrain d'origine…" : "Construction en cours…");
        }
        Job j = current;
        int budget = ConstructionConfig.blocksPerTick;
        try {
            if (j.phase == 0) {
                int total = j.total();
                while (budget-- > 0 && j.index < total) {
                    int i = j.isUndo() ? total - 1 - j.index : j.index;
                    if (j.isUndo()) {
                        CompoundTag be = j.restore.blockEntities.get(i);
                        place(level, j, j.restore.pos(i), j.restore.state(i), be);
                    } else {
                        int s = j.plan.states[i];
                        place(level, j, j.plan.positions[i], j.plan.palette.get(s), j.plan.paletteNbt.get(s));
                    }
                    j.index++;
                }
                j.d.progression = j.index;
                if (j.index >= total) j.phase = 1;
            } else if (j.phase == 1) {
                int total = j.total();
                budget *= 2;
                while (budget-- > 0 && j.connIndex < total) {
                    long p;
                    BlockState s;
                    if (j.isUndo()) { p = j.restore.pos(j.connIndex); s = j.restore.state(j.connIndex); }
                    else { p = j.plan.positions[j.connIndex]; s = j.plan.palette.get(j.plan.states[j.connIndex]); }
                    j.connIndex++;
                    if (!Plan.isConnecting(s)) continue;
                    BlockPos pos = BlockPos.of(p);
                    BlockState cur = level.getBlockState(pos);
                    BlockState fixed = Block.updateFromNeighbourShapes(cur, level, pos);
                    if (fixed != cur) level.setBlock(pos, fixed, FLAGS);
                }
                if (j.connIndex >= total) j.phase = 2;
            }
            if (++j.lastMark % 40 == 0) markMap(j);
            if (j.phase == 2) finish(j);
            else if (System.currentTimeMillis() - lastSave > 5000) { lastSave = System.currentTimeMillis(); store.save(); }
        } catch (Exception e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] Erreur pendant {}", j.d.id, e);
            interrupt(j, "Erreur : " + e.getMessage());
            j.d.statut = Demande.ERREUR;
            store.save();
            current = null;
        }
    }

    private void place(ServerLevel level, Job j, long p, BlockState s, CompoundTag nbt) {
        BlockPos pos = BlockPos.of(p);
        j.minX = Math.min(j.minX, pos.getX()); j.maxX = Math.max(j.maxX, pos.getX());
        j.minZ = Math.min(j.minZ, pos.getZ()); j.maxZ = Math.max(j.maxZ, pos.getZ());
        BlockState old = level.getBlockState(pos); // charge le chunk si besoin
        BlockEntity oldBe = level.getBlockEntity(pos);
        if (j.record != null && !j.record.has(p)) {
            CompoundTag saved = oldBe != null ? oldBe.saveWithFullMetadata(level.registryAccess()) : null;
            j.record.add(p, old, saved);
        }
        if (old == s && nbt == null) return;
        if (oldBe instanceof Container c) c.clearContent(); // pas d'objets qui tombent par terre
        level.setBlock(pos, s, FLAGS);
        if (nbt != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag t = nbt.copy();
                t.putInt("x", pos.getX()); t.putInt("y", pos.getY()); t.putInt("z", pos.getZ());
                be.loadWithComponents(t, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(pos, s, s, Block.UPDATE_CLIENTS);
            }
        }
    }

    private void finish(Job j) {
        markMap(j);
        if (j.isUndo()) {
            try { Files.deleteIfExists(store.undoFile(j.d.id)); } catch (Exception ignored) {}
            j.d.annulable = false;
            store.update(j.d, Demande.ANNULEE, "Terrain remis comme avant.");
        } else {
            try { j.record.save(store.undoFile(j.d.id)); j.d.annulable = true; }
            catch (Exception e) { CitizenConstruction.LOG.error("[CitizenConstruction] Instantané {} non sauvegardé", j.d.id, e); }
            store.update(j.d, Demande.TERMINEE, "Construction terminée : " + j.total() + " blocs.");
            GitHubSync.pushLater(j.d);
        }
        CitizenConstruction.LOG.info("[CitizenConstruction] {} : {}", j.d.id, j.d.message);
        current = null;
    }

    private static void markMap(Job j) {
        if (j.minX == Integer.MAX_VALUE) return;
        MapBridge.markAreaDirty(j.minX, j.minZ, j.maxX, j.maxZ);
    }
}
