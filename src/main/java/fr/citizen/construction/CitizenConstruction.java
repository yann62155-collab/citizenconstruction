package fr.citizen.construction;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.tags.FluidTags;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Citizen Construction : on sélectionne une zone sur la carte 3D (Citizen Map), on décrit ce qu'on veut,
 * Claude renvoie un plan, et le mod le construit en direct dans le monde, sans redémarrer le serveur.
 */
@Mod(CitizenConstruction.MOD_ID)
public class CitizenConstruction {
    public static final String MOD_ID = "citizenconstruction";
    public static final Logger LOG = LogUtils.getLogger();

    private static volatile MinecraftServer server;
    private static volatile ServerLevel level;
    private static volatile DemandeStore store;
    private static volatile Builder builder;
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    public CitizenConstruction(IEventBus modBus) {
        ConstructionConfig.load();
        NeoForge.EVENT_BUS.addListener(CitizenConstruction::onStarted);
        NeoForge.EVENT_BUS.addListener(CitizenConstruction::onStopping);
        NeoForge.EVENT_BUS.addListener(CitizenConstruction::onTick);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ConstructionCommand.register(e.getDispatcher()));
    }

    public static MinecraftServer server() { return server; }
    public static ServerLevel level() { return level; }
    public static DemandeStore store() { return store; }
    public static Builder builder() { return builder; }

    private static void onStarted(ServerStartedEvent e) {
        server = e.getServer();
        level = mapLevel(server);
        store = new DemandeStore(server.getWorldPath(LevelResource.ROOT).resolve("citizenconstruction"));
        builder = new Builder(store);
        if (MapBridge.init()) {
            ConstructionWeb.register();
            LOG.info("[CitizenConstruction] Branché sur la carte : {}", MapBridge.publicUrl());
        }
        GitHubSync.start();
    }

    private static ServerLevel mapLevel(MinecraftServer srv) {
        try {
            Class<?> api = Class.forName("fr.citizen.map.api.CitizenMapApi", true, CitizenConstruction.class.getClassLoader());
            String dim = (String) api.getMethod("dimension").invoke(null);
            ServerLevel l = srv.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)));
            if (l != null) return l;
        } catch (Throwable ignored) {}
        return srv.overworld();
    }

    private static void onStopping(ServerStoppingEvent e) {
        if (builder != null) builder.stopAll("Le serveur s'est arrêté pendant l'opération. Relance « Construire » ou « Annuler ».");
        if (store != null) store.save();
        GitHubSync.stop();
        server = null;
        level = null;
    }

    private static void onTick(ServerTickEvent.Post e) {
        if (builder != null && level != null) builder.tick(level);
    }

    // ------------------------------------------------------------------ demandes

    /** Relevé du terrain de la zone (sur le thread serveur). */
    public static void survey(ServerLevel l, Demande d) {
        int ax = Math.min(d.x0, d.x1), bx = Math.max(d.x0, d.x1), az = Math.min(d.z0, d.z1), bz = Math.max(d.z0, d.z1);
        d.x0 = ax; d.x1 = bx; d.z0 = az; d.z1 = bz;
        int w = bx - ax + 1, len = bz - az + 1;
        d.hauteurs = new int[len][w];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        int water = 0, trees = 0;
        Map<String, Integer> counts = new HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = az; z <= bz; z++) {
            for (int x = ax; x <= bx; x++) {
                LevelChunk c = l.getChunk(x >> 4, z >> 4);
                int ground = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                int top = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                p.set(x, ground, z);
                BlockState s = c.getBlockState(p);
                if (s.getFluidState().is(FluidTags.WATER)) water++;
                if (top > ground + 1) trees++;
                String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                counts.merge(id, 1, Integer::sum);
                d.hauteurs[z - az][x - ax] = ground;
                min = Math.min(min, ground); max = Math.max(max, ground); sum += ground;
            }
        }
        d.solMin = min; d.solMax = max; d.solMoyen = (int) Math.round((double) sum / (w * len));
        d.colonnesEau = water; d.colonnesArbres = trees;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        d.blocsSurface = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(8, top.size()); i++) d.blocsSurface.put(top.get(i).getKey(), top.get(i).getValue());
    }

    /** Plan reçu (GitHub ou import sur le site) : validation sur le thread serveur. Renvoie null si OK, sinon l'erreur. */
    public static String receivePlan(Demande d, String json, String sha, String source) {
        ServerLevel l = level;
        if (l == null) return "serveur non prêt";
        if (builder.busyWith(d.id)) return "cette demande est en cours de construction";
        Plan plan;
        try {
            plan = Plan.compile(json, l, d, ConstructionConfig.maxBlocks);
        } catch (Plan.PlanException ex) {
            if (sha != null) d.planSha = sha; // ne pas reboucler sur un plan cassé
            store.update(d, null, "Plan refusé (" + source + ") : " + ex.getMessage());
            return ex.getMessage();
        }
        try { store.writePlan(d.id, json); } catch (Exception ex) { return "écriture du plan impossible : " + ex.getMessage(); }
        PLANS.put(d.id, plan);
        d.planSha = sha;
        d.planNom = plan.nom;
        d.planBlocs = plan.size();
        d.planOrigine = plan.origine;
        d.planMin = new int[]{plan.minX, plan.minY, plan.minZ};
        d.planMax = new int[]{plan.maxX, plan.maxY, plan.maxZ};
        d.total = plan.size();
        d.progression = 0;
        boolean wasBuilt = Demande.TERMINEE.equals(d.statut) || Demande.INTERROMPUE.equals(d.statut);
        store.update(d, wasBuilt && d.annulable ? d.statut : Demande.PLAN_RECU,
                "Plan « " + plan.nom + " » reçu (" + source + ") : " + plan.size() + " blocs." + (wasBuilt ? " Annule d'abord l'ancienne construction si besoin." : ""));
        notifyOps("Plan reçu pour " + d.id + " (" + d.titre + ") : " + plan.size() + " blocs.");
        if (ConstructionConfig.autoBuild && !wasBuilt) {
            try { builder.startBuild(l, d, plan); } catch (Exception ex) { return ex.getMessage(); }
        }
        return null;
    }

    public static void receivePlanAsync(Demande d, String json, String sha, String source) {
        MinecraftServer s = server;
        if (s != null) s.execute(() -> receivePlan(d, json, sha, source));
    }

    /** Plan compilé (mémoire, sinon relu depuis le disque). Thread serveur. */
    public static Plan plan(Demande d) throws Exception {
        Plan p = PLANS.get(d.id);
        if (p != null) return p;
        p = Plan.compile(store.readPlan(d.id), level, d, ConstructionConfig.maxBlocks);
        PLANS.put(d.id, p);
        return p;
    }

    public static String build(Demande d) {
        try {
            if (builder.busyWith(d.id)) return "déjà en cours";
            if (d.planBlocs <= 0) return "pas encore de plan pour " + d.id;
            builder.startBuild(level, d, plan(d));
            notifyOps("Construction de " + d.id + " (" + d.titre + ") lancée.");
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    public static String undo(Demande d) {
        try {
            if (builder.busyWith(d.id)) return "déjà en cours";
            builder.startUndo(level, d);
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    public static void forget(String id) { PLANS.remove(id); }

    static void notifyOps(String msg) {
        MinecraftServer s = server;
        if (s == null) return;
        net.minecraft.network.chat.Component c = net.minecraft.network.chat.Component.literal("[Construction] " + msg)
                .withStyle(net.minecraft.ChatFormatting.GOLD);
        for (var p : s.getPlayerList().getPlayers()) if (p.hasPermissions(2)) p.sendSystemMessage(c);
    }

    /** Texte complet à donner à Claude (bouton « Copier pour Claude »). */
    public static String textForClaude(Demande d) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEMANDE DE CONSTRUCTION ").append(d.id).append(" — serveur Citizen\n");
        sb.append("Titre : ").append(d.titre).append('\n');
        sb.append("Ce que je veux : ").append(d.description).append("\n\n");
        sb.append("Zone sélectionnée : X ").append(d.x0).append(" → ").append(d.x1)
                .append(", Z ").append(d.z0).append(" → ").append(d.z1)
                .append(" (").append(d.largeur()).append(" × ").append(d.longueur()).append(" blocs)\n");
        sb.append("Hauteur autorisée : Y ").append(d.yMin).append(" → ").append(d.yMax).append('\n');
        sb.append("Sol : Y min ").append(d.solMin).append(", max ").append(d.solMax).append(", moyen ").append(d.solMoyen)
                .append(" — colonnes d'eau : ").append(d.colonnesEau).append(", colonnes avec arbres : ").append(d.colonnesArbres).append('\n');
        sb.append("Blocs en surface : ");
        d.blocsSurface.forEach((k, v) -> sb.append(k).append(" ×").append(v).append("  "));
        sb.append("\n\nRéponse attendue : un plan JSON au format « citizen-plan-1 » (demande \"").append(d.id)
                .append("\"), déposé dans plans/").append(d.id).append(".json du dépôt ").append(ConstructionConfig.githubRepo)
                .append(" ou importé sur la carte. Le plan peut déborder de 16 blocs au maximum autour de la zone.\n");
        if (d.hauteurs != null) {
            sb.append("\nHauteur du sol (Y du bloc de sol) — une ligne par Z, valeurs pour X de ").append(d.x0).append(" à ").append(d.x1).append(" :\n");
            for (int z = 0; z < d.hauteurs.length; z++) {
                sb.append("z=").append(d.z0 + z).append(':');
                for (int v : d.hauteurs[z]) sb.append(' ').append(v);
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
