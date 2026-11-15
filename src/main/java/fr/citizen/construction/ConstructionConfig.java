package fr.citizen.construction;

import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/citizenconstruction.properties — relu par /construction recharger (sans redémarrage). */
public final class ConstructionConfig {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("citizenconstruction.properties");

    public static int blocksPerTick = 800;
    public static int maxSize = 256;
    public static int maxHeight = 160;
    public static int maxBlocks = 1_500_000;
    public static boolean autoBuild = false;
    public static String githubToken = "";
    public static String githubRepo = "yann62155-collab/citizenconstruction";
    public static String githubBranch = "main";
    public static int githubIntervalSeconds = 30;

    private ConstructionConfig() {}

    public static synchronized void load() {
        Properties p = new Properties();
        try {
            if (Files.exists(FILE)) try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { p.load(r); }
        } catch (Exception e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] Lecture de {} impossible", FILE, e);
        }
        blocksPerTick = Math.max(10, intOf(p, "blocs.par.tick", 800));
        maxSize = Math.max(8, intOf(p, "selection.taille.max", 256));
        maxHeight = Math.max(8, intOf(p, "selection.hauteur.max", 160));
        maxBlocks = Math.max(1000, intOf(p, "plan.blocs.max", 1_500_000));
        autoBuild = Boolean.parseBoolean(p.getProperty("construction.auto", "false").trim());
        githubToken = p.getProperty("github.token", "").trim();
        githubRepo = p.getProperty("github.depot", "yann62155-collab/citizenconstruction").trim();
        githubBranch = p.getProperty("github.branche", "main").trim();
        githubIntervalSeconds = Math.max(10, intOf(p, "github.intervalle.secondes", 30));
        save();
    }

    public static synchronized void save() {
        Properties p = new Properties();
        p.setProperty("blocs.par.tick", String.valueOf(blocksPerTick));
        p.setProperty("selection.taille.max", String.valueOf(maxSize));
        p.setProperty("selection.hauteur.max", String.valueOf(maxHeight));
        p.setProperty("plan.blocs.max", String.valueOf(maxBlocks));
        p.setProperty("construction.auto", String.valueOf(autoBuild));
        p.setProperty("github.token", githubToken);
        p.setProperty("github.depot", githubRepo);
        p.setProperty("github.branche", githubBranch);
        p.setProperty("github.intervalle.secondes", String.valueOf(githubIntervalSeconds));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                p.store(w, "Citizen Construction - github.token : jeton GitHub (Contents: lecture/ecriture) pour le pont automatique avec Claude");
            }
        } catch (Exception e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] Écriture de {} impossible", FILE, e);
        }
    }

    public static boolean githubEnabled() { return !githubToken.isEmpty() && githubRepo.contains("/"); }

    private static int intOf(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
