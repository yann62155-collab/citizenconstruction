package fr.citizen.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pont automatique avec Claude via GitHub (optionnel, actif si github.token est rempli) :
 *  - chaque demande est envoyée dans demandes/C-0001.json du dépôt ;
 *  - Claude dépose son plan dans plans/C-0001.json ;
 *  - le mod relève le dossier plans/ toutes les 30 s et charge le plan à chaud.
 */
public final class GitHubSync {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static ScheduledExecutorService exec;
    private static ScheduledFuture<?> pollTask;
    private static volatile String status = "désactivé (github.token vide)";
    private static volatile long lastPoll;

    private GitHubSync() {}

    public static String status() { return status; }
    public static long lastPoll() { return lastPoll; }

    public static synchronized void start() {
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CitizenConstruction-GitHub");
                t.setDaemon(true);
                return t;
            });
        }
        if (pollTask != null) pollTask.cancel(false);
        if (!ConstructionConfig.githubEnabled()) { status = "désactivé (github.token vide)"; return; }
        status = "actif — dépôt " + ConstructionConfig.githubRepo;
        pollTask = exec.scheduleWithFixedDelay(GitHubSync::pollSafe, 5, ConstructionConfig.githubIntervalSeconds, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (exec != null) exec.shutdownNow();
        exec = null;
        pollTask = null;
    }

    public static void pollNow() {
        ScheduledExecutorService e = exec;
        if (e != null && ConstructionConfig.githubEnabled()) e.submit(GitHubSync::pollSafe);
    }

    public static void pushLater(Demande d) {
        ScheduledExecutorService e = exec;
        if (e == null || !ConstructionConfig.githubEnabled()) return;
        String json = DemandeStore.GSON.toJson(d);
        String id = d.id;
        e.submit(() -> {
            try { putFile("demandes/" + id + ".json", json, "Demande " + id + " : " + d.titre + " (" + d.statut + ")"); }
            catch (Exception ex) {
                status = "erreur d'envoi : " + ex.getMessage();
                CitizenConstruction.LOG.warn("[CitizenConstruction] Envoi GitHub de {} impossible : {}", id, ex.getMessage());
            }
        });
    }

    private static void pollSafe() {
        try {
            poll();
            lastPoll = System.currentTimeMillis();
            status = "actif — dépôt " + ConstructionConfig.githubRepo;
        } catch (Exception ex) {
            status = "erreur : " + ex.getMessage();
            CitizenConstruction.LOG.warn("[CitizenConstruction] Relève GitHub impossible : {}", ex.getMessage());
        }
    }

    private static void poll() throws Exception {
        HttpResponse<String> r = send(req("contents/plans?ref=" + enc(ConstructionConfig.githubBranch)).GET().build());
        if (r.statusCode() == 404) return; // pas encore de dossier plans/
        if (r.statusCode() != 200) throw new IllegalStateException("GitHub a répondu " + r.statusCode());
        JsonElement el = JsonParser.parseString(r.body());
        if (!el.isJsonArray()) return;
        for (JsonElement e : (JsonArray) el) {
            JsonObject f = e.getAsJsonObject();
            String name = f.get("name").getAsString();
            if (!"file".equals(f.get("type").getAsString()) || !name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - 5);
            Demande d = CitizenConstruction.store() == null ? null : CitizenConstruction.store().get(id);
            if (d == null) continue;
            String sha = f.get("sha").getAsString();
            if (sha.equals(d.planSha)) continue;
            HttpResponse<String> raw = send(req("contents/plans/" + enc(name) + "?ref=" + enc(ConstructionConfig.githubBranch))
                    .setHeader("Accept", "application/vnd.github.raw+json").GET().build());
            if (raw.statusCode() != 200) continue;
            CitizenConstruction.LOG.info("[CitizenConstruction] Plan reçu de GitHub pour {}", id);
            CitizenConstruction.receivePlanAsync(d, raw.body(), sha, "GitHub");
        }
    }

    private static void putFile(String path, String content, String message) throws Exception {
        String sha = null;
        HttpResponse<String> g = send(req("contents/" + path + "?ref=" + enc(ConstructionConfig.githubBranch)).GET().build());
        if (g.statusCode() == 200) sha = JsonParser.parseString(g.body()).getAsJsonObject().get("sha").getAsString();
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.addProperty("branch", ConstructionConfig.githubBranch);
        if (sha != null) body.addProperty("sha", sha);
        HttpResponse<String> r = send(req("contents/" + path).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        if (r.statusCode() != 200 && r.statusCode() != 201) throw new IllegalStateException("GitHub a répondu " + r.statusCode());
    }

    private static HttpRequest.Builder req(String apiPath) {
        return HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + ConstructionConfig.githubRepo + "/" + apiPath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ConstructionConfig.githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "CitizenConstruction");
    }

    private static HttpResponse<String> send(HttpRequest r) throws Exception {
        return HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
