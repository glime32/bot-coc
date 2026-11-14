package fr.glime.botcoc;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Copie les modèles du bot (templates/ du PC, dans l'appli) vers les fichiers de l'appli, sans
 *  écraser ce que le bot a appris sur le téléphone (cartes, bords de la carte, boutons). */
final class Fichiers {
    private Fichiers() {}

    static synchronized void installer(Context c) {
        Config.racine = c.getExternalFilesDir(null);
        AssetManager am = c.getAssets();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(am.open("liste.txt"), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.startsWith("./")) l = l.substring(2);
                if (l.isEmpty()) continue;
                File f = new File(Config.templates(), l);
                if (f.exists()) continue;
                f.getParentFile().mkdirs();
                try (InputStream in = am.open("templates/" + l); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
            }
        } catch (IOException e) {
            Journal.log("Modèles du bot illisibles : " + e.getMessage());
        }
        Config.debugDossier().mkdirs();
    }
}
