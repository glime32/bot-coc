package fr.glime.botcoc;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Lancer le bot de n'importe où (appli, commande adb) : allume le service d'accessibilité s'il
 *  est coupé (sans fenêtre : permission donnée par adb), attend qu'il soit prêt, puis ouvre le
 *  partage d'écran (sans question : autorisation à vie) et démarre le bot. */
final class Demarrage {
    private Demarrage() {}

    static void lancer(Context c, Bundle essai, Runnable fini) {
        Context app = c.getApplicationContext();
        if (!Acces.assurer(app)) {
            Journal.log("Le service d'accessibilité Bot CoC est coupé et l'appli n'a pas le droit de le rallumer.");
            if (fini != null) fini.run();
            return;
        }
        new Thread(() -> {
            try {
                for (int k = 0; k < 60 && BotService.instance == null; k++) Thread.sleep(100);
                BotService s = BotService.instance;
                if (s == null) {
                    Journal.log("Le service d'accessibilité ne démarre pas.");
                    return;
                }
                if (CaptureService.actif()) {
                    Controle.lancer(app, essai);
                } else {
                    Intent ci = new Intent(s, ConsentActivity.class).putExtra("lancer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (essai != null) ci.putExtra("essai", essai);
                    s.startActivity(ci);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (fini != null) fini.run();
            }
        }, "demarrage").start();
    }
}
