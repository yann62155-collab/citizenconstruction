package fr.citizen.construction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * world/citizenconstruction/
 *   demandes.json            liste des demandes
 *   plans/C-0001.json        plans reçus (format citizen-plan-1)
 *   annulations/C-0001.nbt   instantané du terrain avant construction
 */
public final class DemandeStore {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path dir;
    private final Map<String, Demande> demandes = new LinkedHashMap<>();
    private int nextNumber = 1;

    public DemandeStore(Path dir) {
        this.dir = dir;
        load();
    }

    public Path dir() { return dir; }
    public Path planFile(String id) { return dir.resolve("plans").resolve(id + ".json"); }
    public Path undoFile(String id) { return dir.resolve("annulations").resolve(id + ".nbt"); }

    private synchronized void load() {
        Path f = dir.resolve("demandes.json");
        if (!Files.exists(f)) return;
        try {
            List<Demande> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Demande>>() {}.getType());
            if (list != null) for (Demande d : list) {
                demandes.put(d.id, d);
                // une construction coupée par un arrêt du serveur
                if (Demande.EN_CONSTRUCTION.equals(d.statut) || Demande.ANNULATION.equals(d.statut)) {
                    d.statut = Demande.INTERROMPUE;
                    d.message = "Le serveur s'est arrêté pendant l'opération. Relance « Construire » ou « Annuler ».";
                }
                nextNumber = Math.max(nextNumber, number(d.id) + 1);
            }
        } catch (Exception e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] demandes.json illisible", e);
        }
    }

    private static int number(String id) {
        try { return Integer.parseInt(id.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve("demandes.json"), tmp = dir.resolve("demandes.json.tmp");
            Files.writeString(tmp, GSON.toJson(new ArrayList<>(demandes.values())), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CitizenConstruction.LOG.error("[CitizenConstruction] Sauvegarde des demandes impossible", e);
        }
    }

    public synchronized Demande create(Demande d) {
        d.id = String.format(Locale.ROOT, "C-%04d", nextNumber++);
        d.creeLe = d.majLe = System.currentTimeMillis();
        demandes.put(d.id, d);
        save();
        return d;
    }

    public synchronized Demande get(String id) { return id == null ? null : demandes.get(id); }

    public synchronized List<Demande> all() {
        List<Demande> l = new ArrayList<>(demandes.values());
        Collections.reverse(l);
        return l;
    }

    public synchronized void remove(String id) {
        demandes.remove(id);
        try { Files.deleteIfExists(planFile(id)); Files.deleteIfExists(undoFile(id)); } catch (IOException ignored) {}
        save();
    }

    public synchronized void update(Demande d, String statut, String message) {
        if (statut != null) d.statut = statut;
        if (message != null) d.message = message;
        d.majLe = System.currentTimeMillis();
        save();
    }

    public String readPlan(String id) throws IOException {
        return Files.readString(planFile(id), StandardCharsets.UTF_8);
    }

    public void writePlan(String id, String json) throws IOException {
        Files.createDirectories(planFile(id).getParent());
        Files.writeString(planFile(id), json, StandardCharsets.UTF_8);
    }
}
