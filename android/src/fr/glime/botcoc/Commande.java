package fr.glime.botcoc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Commandes depuis le PC (adb, réservées au shell par la permission DUMP) :
 *  am broadcast -n fr.glime.botcoc/.Commande -a fr.glime.botcoc.LANCER [--ei attaques 1 --ez debug true]
 *  … ARRETER, CAPTURE (enregistre l'écran vu par le bot dans debug/). */
public final class Commande extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case "fr.glime.botcoc.ARRETER":
                Controle.arreter();
                break;
            case "fr.glime.botcoc.LANCER":
                if (CaptureService.actif() && BotService.instance != null) {
                    Controle.lancer(c, i.getExtras());
                } else {
                    PendingResult attente = goAsync();
                    Demarrage.lancer(c, i.getExtras(), attente::finish);
                }
                break;
            case "fr.glime.botcoc.LUMIERE":
                if (BotService.instance != null) BotService.instance.essaiLumiere(i.getIntExtra("ms", 3000));
                break;
            case "fr.glime.botcoc.CAPTURE":
                PendingResult r = goAsync();
                new Thread(() -> {
                    CaptureService s = CaptureService.instance;
                    try {
                        Img img = s != null ? s.image(500) : null;
                        if (img == null) {
                            Journal.log("Capture : pas d'image (partage d'écran fermé ?)");
                        } else {
                            Journal.log("Capture : " + Combat.sauver(img, "ecran") + " (" + img.w + "×" + img.h + ")");
                        }
                    } finally {
                        if (s != null && !Controle.enMarche()) s.veille(true);
                        r.finish();
                    }
                }).start();
                break;
            default:
        }
    }
}
