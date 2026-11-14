package fr.glime.botcoc;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Journal du bot : écran (pastille, appli), fichier journal.txt et sortie standard. */
final class Journal {
    private Journal() {}

    interface Ecouteur { void ligne(String ligne); }

    private static final ArrayDeque<String> lignes = new ArrayDeque<>();
    private static final List<Ecouteur> ecouteurs = new ArrayList<>();
    private static Writer fichier;
    static boolean console = false;

    static synchronized void ouvrir(File f) {
        fermer();
        try {
            f.getParentFile().mkdirs();
            fichier = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fichier = null;
        }
        lignes.clear();
    }

    static synchronized void fermer() {
        if (fichier != null) {
            try { fichier.close(); } catch (IOException ignored) {}
            fichier = null;
        }
    }

    static String heure() {
        return new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date());
    }

    static void log(String message) {
        String l = heure() + " " + message;
        List<Ecouteur> copie;
        synchronized (Journal.class) {
            lignes.addLast(l);
            while (lignes.size() > 300) lignes.removeFirst();
            if (fichier != null) {
                try { fichier.write(l + "\n"); fichier.flush(); } catch (IOException ignored) {}
            }
            copie = new ArrayList<>(ecouteurs);
        }
        if (console) System.out.println(l);
        for (Ecouteur e : copie) e.ligne(l);
    }

    static synchronized List<String> dernieres(int n) {
        List<String> tout = new ArrayList<>(lignes);
        return tout.subList(Math.max(0, tout.size() - n), tout.size());
    }

    static synchronized void ecouter(Ecouteur e) { ecouteurs.add(e); }

    static synchronized void oublier(Ecouteur e) { ecouteurs.remove(e); }
}
