package fr.glime.botcoc;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/** Le service d'accessibilité de Bot CoC : remis tout seul s'il a été retiré (permission
 *  WRITE_SECURE_SETTINGS donnée par adb), sans toucher aux services des autres applis. */
final class Acces {
    private Acces() {}

    static String moi(Context c) { return new ComponentName(c, BotService.class).flattenToString(); }

    static boolean actif(Context c) {
        String liste = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(liste)) return false;
        ComponentName moi = new ComponentName(c, BotService.class);
        for (String s : liste.split(":")) if (moi.equals(ComponentName.unflattenFromString(s.trim()))) return true;
        return false;
    }

    /** true si le service est dans la liste (déjà, ou rajouté maintenant). */
    static boolean assurer(Context c) {
        if (actif(c)) return true;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String suite = TextUtils.isEmpty(cur) ? moi(c) : cur + ":" + moi(c);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, suite);
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            Journal.log("Service d'accessibilité Bot CoC remis.");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
