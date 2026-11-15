package fr.citizen.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** Routes /construction/… ajoutées au site de Citizen Map. */
public final class ConstructionWeb {
    private ConstructionWeb() {}

    public static void register() {
        MapBridge.addContext("/construction/", ConstructionWeb::handle);
        MapBridge.addScript("/construction/ui.js");
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (path.equals("/construction/ui.js")) {
            try (InputStream in = ConstructionWeb.class.getResourceAsStream("/citizenconstruction/web/ui.js")) {
                if (in == null) { text(ex, 404, "introuvable"); return; }
                bytes(ex, 200, "text/javascript; charset=utf-8", in.readAllBytes());
            }
            return;
        }
        if (!path.startsWith("/construction/api/")) { text(ex, 404, "introuvable"); return; }
        if (!MapBridge.isAdmin(ex)) { json(ex, 403, err("Connexion admin requise")); return; }
        if (CitizenConstruction.store() == null || CitizenConstruction.server() == null) { json(ex, 503, err("Serveur en démarrage")); return; }
        String action = path.substring("/construction/api/".length());
        Map<String, String> q = query(ex);
        DemandeStore store = CitizenConstruction.store();
        try {
            switch (action) {
                case "demandes" -> list(ex);
                case "demande" -> {
                    if ("POST".equalsIgnoreCase(ex.getRequestMethod())) create(ex);
                    else {
                        Demande d = store.get(q.get("id"));
                        if (d == null) json(ex, 404, err("Demande introuvable"));
                        else json(ex, 200, DemandeStore.GSON.toJson(d));
                    }
                }
                case "texte" -> {
                    Demande d = store.get(q.get("id"));
                    if (d == null) json(ex, 404, err("Demande introuvable"));
                    else text(ex, 200, CitizenConstruction.textForClaude(d));
                }
                case "plan" -> {
                    requirePost(ex);
                    Demande d = store.get(q.get("id"));
                    if (d == null) { json(ex, 404, err("Demande introuvable")); return; }
                    String body = readBody(ex, 40 * 1024 * 1024);
                    String e = onServer(() -> CitizenConstruction.receivePlan(d, body, null, "site"));
                    if (e != null) json(ex, 400, err(e)); else json(ex, 200, ok(d.message));
                }
                case "apercu" -> preview(ex, store.get(q.get("id")));
                case "construire" -> {
                    requirePost(ex);
                    Demande d = store.get(q.get("id"));
                    if (d == null) { json(ex, 404, err("Demande introuvable")); return; }
                    String e = onServer(() -> CitizenConstruction.build(d));
                    if (e != null) json(ex, 400, err(e)); else json(ex, 200, ok("Construction lancée"));
                }
                case "annuler" -> {
                    requirePost(ex);
                    Demande d = store.get(q.get("id"));
                    if (d == null) { json(ex, 404, err("Demande introuvable")); return; }
                    String e = onServer(() -> CitizenConstruction.undo(d));
                    if (e != null) json(ex, 400, err(e)); else json(ex, 200, ok("Remise du terrain lancée"));
                }
                case "stop" -> {
                    requirePost(ex);
                    onServer(() -> { CitizenConstruction.builder().stopAll("Arrêtée depuis la carte."); return null; });
                    json(ex, 200, ok("Arrêté"));
                }
                case "supprimer" -> {
                    requirePost(ex);
                    Demande d = store.get(q.get("id"));
                    if (d == null) { json(ex, 404, err("Demande introuvable")); return; }
                    if (CitizenConstruction.builder().busyWith(d.id)) { json(ex, 400, err("Arrête d'abord l'opération en cours")); return; }
                    store.remove(d.id);
                    CitizenConstruction.forget(d.id);
                    json(ex, 200, ok("Supprimée"));
                }
                case "github" -> { requirePost(ex); GitHubSync.pollNow(); json(ex, 200, ok("Relève lancée")); }
                default -> json(ex, 404, err("Action inconnue"));
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, err(e.getMessage()));
        } catch (Exception e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] {}", path, e);
            json(ex, 500, err(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static void list(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Demande d : CitizenConstruction.store().all()) arr.add(DemandeStore.GSON.toJsonTree(d.leger()));
        o.add("demandes", arr);
        Builder.Job j = CitizenConstruction.builder().current();
        if (j != null) {
            JsonObject job = new JsonObject();
            job.addProperty("id", j.d.id);
            job.addProperty("annulation", j.isUndo());
            job.addProperty("progression", j.d.progression);
            job.addProperty("total", j.d.total);
            o.add("travail", job);
        }
        o.addProperty("fileAttente", CitizenConstruction.builder().queued());
        JsonObject gh = new JsonObject();
        gh.addProperty("actif", ConstructionConfig.githubEnabled());
        gh.addProperty("etat", GitHubSync.status());
        gh.addProperty("depot", ConstructionConfig.githubRepo);
        gh.addProperty("derniereReleve", GitHubSync.lastPoll());
        o.add("github", gh);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("tailleMax", ConstructionConfig.maxSize);
        cfg.addProperty("hauteurMax", ConstructionConfig.maxHeight);
        cfg.addProperty("auto", ConstructionConfig.autoBuild);
        cfg.addProperty("blocsParTick", ConstructionConfig.blocksPerTick);
        o.add("config", cfg);
        json(ex, 200, o.toString());
    }

    private static void create(HttpExchange ex) throws Exception {
        JsonObject in = JsonParser.parseString(readBody(ex, 64 * 1024)).getAsJsonObject();
        Demande d = new Demande();
        d.titre = str(in, "titre", "Construction");
        d.description = str(in, "description", "");
        if (d.titre.length() > 120) d.titre = d.titre.substring(0, 120);
        if (d.description.length() > 8000) d.description = d.description.substring(0, 8000);
        if (d.description.isBlank()) throw new IllegalArgumentException("Décris ce que tu veux construire");
        d.x0 = in.get("x0").getAsInt(); d.z0 = in.get("z0").getAsInt();
        d.x1 = in.get("x1").getAsInt(); d.z1 = in.get("z1").getAsInt();
        int hauteur = in.has("hauteur") ? in.get("hauteur").getAsInt() : 24;
        d.auteur = str(in, "auteur", "admin carte");
        int w = Math.abs(d.x1 - d.x0) + 1, l = Math.abs(d.z1 - d.z0) + 1;
        if (w > ConstructionConfig.maxSize || l > ConstructionConfig.maxSize)
            throw new IllegalArgumentException("Zone trop grande (max " + ConstructionConfig.maxSize + " × " + ConstructionConfig.maxSize + ")");
        if (w < 1 || l < 1) throw new IllegalArgumentException("Zone vide");
        hauteur = Math.max(4, Math.min(ConstructionConfig.maxHeight, hauteur));
        int h = hauteur;
        onServer(() -> {
            CitizenConstruction.survey(CitizenConstruction.level(), d);
            d.yMin = Math.max(CitizenConstruction.level().getMinBuildHeight(), d.solMin - 6);
            d.yMax = Math.min(CitizenConstruction.level().getMaxBuildHeight() - 1, d.solMax + h);
            return null;
        });
        CitizenConstruction.store().create(d);
        GitHubSync.pushLater(d);
        CitizenConstruction.notifyOps("Nouvelle demande " + d.id + " : " + d.titre);
        json(ex, 200, DemandeStore.GSON.toJson(d.leger()));
    }

    /** Blocs visibles du plan (format binaire compact) pour l'aperçu fantôme en 3D. */
    private static void preview(HttpExchange ex, Demande d) throws Exception {
        if (d == null) { json(ex, 404, err("Demande introuvable")); return; }
        byte[] data = onServer(() -> {
            try {
                Plan p = CitizenConstruction.plan(d);
                LongIntMap solid = new LongIntMap(p.size());
                for (int i = 0; i < p.size(); i++) {
                    BlockState s = p.palette.get(p.states[i]);
                    if (!s.isAir()) solid.put(p.positions[i], s.canOcclude() ? 1 : 2);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bos);
                out.writeInt(0x43505631); // CPV1
                out.writeInt(p.minX); out.writeInt(p.minY); out.writeInt(p.minZ);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                DataOutputStream b = new DataOutputStream(body);
                int count = 0;
                Map<Integer, Integer> colors = new HashMap<>();
                for (int i = 0; i < p.size() && count < 400_000; i++) {
                    long pos = p.positions[i];
                    BlockState s = p.palette.get(p.states[i]);
                    if (s.isAir()) continue;
                    boolean exposed = false;
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                        if (solid.get(BlockPos.offset(pos, dir), 0) != 1) { exposed = true; break; }
                    }
                    if (!exposed) continue;
                    int col = colors.computeIfAbsent(p.states[i], k -> {
                        MapColor mc = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                        return mc == MapColor.NONE ? 0xB0B0B0 : mc.col;
                    });
                    b.writeShort(BlockPos.getX(pos) - p.minX);
                    b.writeShort(BlockPos.getY(pos) - p.minY);
                    b.writeShort(BlockPos.getZ(pos) - p.minZ);
                    b.writeByte((col >> 16) & 255); b.writeByte((col >> 8) & 255); b.writeByte(col & 255);
                    count++;
                }
                out.writeInt(count);
                out.write(body.toByteArray());
                return bos.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        bytes(ex, 200, "application/octet-stream", data);
    }

    // ------------------------------------------------------------------ outils

    private static <T> T onServer(Supplier<T> task) throws Exception {
        MinecraftServer s = CitizenConstruction.server();
        if (s == null) throw new IllegalStateException("serveur arrêté");
        try {
            return s.submit(task).get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw new IllegalArgumentException(c != null && c.getMessage() != null ? c.getMessage() : String.valueOf(c));
        }
    }

    private static void requirePost(HttpExchange ex) {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) throw new IllegalArgumentException("POST attendu");
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : def;
    }

    private static String err(String m) { JsonObject o = new JsonObject(); o.addProperty("erreur", m); return o.toString(); }
    private static String ok(String m) { JsonObject o = new JsonObject(); o.addProperty("ok", true); o.addProperty("message", m); return o.toString(); }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String readBody(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > max) throw new IllegalArgumentException("Fichier trop gros");
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange ex, int code, String s) throws IOException { bytes(ex, code, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
    private static void text(HttpExchange ex, int code, String s) throws IOException { bytes(ex, code, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }

    private static void bytes(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        String enc = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (data.length > 1024 && enc != null && enc.contains("gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(data); }
            data = bos.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
