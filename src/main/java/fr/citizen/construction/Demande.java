package fr.citizen.construction;

import java.util.LinkedHashMap;
import java.util.Map;

/** Une demande de construction (sérialisée en JSON avec Gson). */
public class Demande {
    public static final String EN_ATTENTE = "en_attente";       // envoyée, Claude n'a pas encore livré de plan
    public static final String PLAN_RECU = "plan_recu";         // plan prêt, en attente du bouton Construire
    public static final String EN_CONSTRUCTION = "en_construction";
    public static final String TERMINEE = "terminee";
    public static final String INTERROMPUE = "interrompue";
    public static final String ANNULATION = "annulation";       // remise du terrain d'origine en cours
    public static final String ANNULEE = "annulee";              // terrain remis comme avant
    public static final String ERREUR = "erreur";

    public String id;
    public String titre = "";
    public String description = "";
    public int x0, z0, x1, z1;
    public int yMin, yMax;
    public String auteur = "";
    public long creeLe;
    public long majLe;
    public String statut = EN_ATTENTE;
    public String message = "";

    // Terrain au moment de la demande (pour que Claude dessine le plan au bon niveau)
    public int solMin, solMax, solMoyen;
    public int colonnesEau;
    public int colonnesArbres;
    public Map<String, Integer> blocsSurface = new LinkedHashMap<>();
    /** hauteurs[z][x] du sol (sans les feuilles), z de z0 à z1, x de x0 à x1. */
    public int[][] hauteurs;

    // Plan livré
    public String planNom;
    public String planSha;
    public int planBlocs;
    public int[] planOrigine;
    public int[] planMin, planMax;

    // Construction
    public int progression;   // blocs posés
    public int total;         // blocs à poser
    public boolean annulable; // un instantané du terrain existe

    public int largeur() { return Math.abs(x1 - x0) + 1; }
    public int longueur() { return Math.abs(z1 - z0) + 1; }

    /** Copie légère pour les listes (sans la grille de hauteurs). */
    public Demande leger() {
        Demande d = new Demande();
        d.id = id; d.titre = titre; d.description = description; d.x0 = x0; d.z0 = z0; d.x1 = x1; d.z1 = z1;
        d.yMin = yMin; d.yMax = yMax; d.auteur = auteur; d.creeLe = creeLe; d.majLe = majLe; d.statut = statut;
        d.message = message; d.solMin = solMin; d.solMax = solMax; d.solMoyen = solMoyen; d.colonnesEau = colonnesEau;
        d.colonnesArbres = colonnesArbres; d.blocsSurface = blocsSurface; d.hauteurs = null;
        d.planNom = planNom; d.planSha = planSha; d.planBlocs = planBlocs; d.planOrigine = planOrigine;
        d.planMin = planMin; d.planMax = planMax; d.progression = progression; d.total = total; d.annulable = annulable;
        return d;
    }
}
