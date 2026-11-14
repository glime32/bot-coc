package fr.glime.botcoc;

import android.content.Context;
import android.content.SharedPreferences;

/** Réglages de l'appli (mêmes valeurs par défaut que l'appli du PC). */
final class Reglages {
    private Reglages() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("reglages", Context.MODE_PRIVATE);
    }
}
