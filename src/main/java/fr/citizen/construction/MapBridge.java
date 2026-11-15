package fr.citizen.construction;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.lang.reflect.Method;

/**
 * Lien avec Citizen Map (fr.citizen.map.api.CitizenMapApi), par réflexion :
 * les deux mods se compilent séparément, seuls des types Java standard passent entre eux.
 */
public final class MapBridge {
    private static Method addContext, addScript, isAdmin, markArea, publicUrl;
    private static boolean ok;

    private MapBridge() {}

    public static synchronized boolean init() {
        if (ok) return true;
        try {
            Class<?> api = Class.forName("fr.citizen.map.api.CitizenMapApi", true, MapBridge.class.getClassLoader());
            addContext = api.getMethod("addContext", String.class, HttpHandler.class);
            addScript = api.getMethod("addScript", String.class);
            isAdmin = api.getMethod("isAdmin", HttpExchange.class);
            markArea = api.getMethod("markAreaDirty", int.class, int.class, int.class, int.class);
            publicUrl = api.getMethod("publicUrl");
            ok = true;
        } catch (Throwable t) {
            CitizenConstruction.LOG.error("[CitizenConstruction] Citizen Map introuvable : installe citizenmap sur le serveur.", t);
            ok = false;
        }
        return ok;
    }

    public static void addContext(String path, HttpHandler h) {
        if (!ok) return;
        try { addContext.invoke(null, path, h); } catch (Throwable t) { CitizenConstruction.LOG.error("[CitizenConstruction] addContext", t); }
    }

    public static void addScript(String url) {
        if (!ok) return;
        try { addScript.invoke(null, url); } catch (Throwable t) { CitizenConstruction.LOG.error("[CitizenConstruction] addScript", t); }
    }

    public static boolean isAdmin(HttpExchange ex) {
        if (!ok) return false;
        try { return (Boolean) isAdmin.invoke(null, ex); } catch (Throwable t) { return false; }
    }

    public static void markAreaDirty(int x0, int z0, int x1, int z1) {
        if (!ok) return;
        try { markArea.invoke(null, x0, z0, x1, z1); } catch (Throwable ignored) {}
    }

    public static String publicUrl() {
        if (!ok) return "";
        try { return (String) publicUrl.invoke(null); } catch (Throwable t) { return ""; }
    }
}
