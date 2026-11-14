package fr.glime.botcoc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Service d'accessibilité : les doigts du bot (touches, glissements, pincements), la pastille
 *  par-dessus le jeu, et l'arrêt par un appui long sur volume bas. */
public final class BotService extends AccessibilityService {
    static volatile BotService instance;

    private static final int MAX_TRAITS = 20;
    private static final long APPUI_LONG_MS = 800;

    private Handler principal;
    private HandlerThread filGestes;
    private Handler gestes;
    private Overlay overlay;
    private volatile String premierPlan = "";
    private volatile long verifie = 0;
    private boolean volumeTenu = false, volumeLong = false;
    private final Runnable arretVolume = () -> {
        volumeLong = true;
        if (Controle.enMarche()) {
            Journal.log("Volume bas tenu : arrêt.");
            Controle.arreter();
            vibrer();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Config.opencv();
        principal = new Handler(Looper.getMainLooper());
        filGestes = new HandlerThread("gestes");
        filGestes.start();
        gestes = new Handler(filGestes.getLooper());
        overlay = new Overlay(this);
        instance = this;
        Config.racine = getExternalFilesDir(null);
        Fichiers.installer(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (p.equals(getPackageName())) return;
        premierPlan = p;
        verifie = System.currentTimeMillis();
        if (overlay != null) overlay.jeuAuPremierPlan(p.equals(Config.JEU));
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Controle.arreter();
        if (overlay != null) overlay.retirer();
        overlay = null;
        if (filGestes != null) filGestes.quitSafely();
        return super.onUnbind(intent);
    }

    /** Appli au premier plan. Les changements d'appli arrivent par les événements ; la fenêtre
     *  active (question posée au jeu lui-même) n'est revérifiée que toutes les 3 s quand c'est le
     *  jeu, toutes les 0,4 s sinon (le jeu peut revenir sans événement, après le volet des
     *  notifications par exemple). */
    String premierPlan() {
        long t = System.currentTimeMillis();
        if (t - verifie > (Config.JEU.equals(premierPlan) ? 3000 : 400)) {
            verifie = t;
            AccessibilityNodeInfo racine = null;
            try {
                racine = getRootInActiveWindow();
                if (racine != null && racine.getPackageName() != null) {
                    String p = racine.getPackageName().toString();
                    if (!p.equals(getPackageName())) premierPlan = p;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return premierPlan;
    }

    boolean jeuAuPremierPlan() { return Config.JEU.equals(premierPlan()); }

    // ── volume bas : appui long = arrêt du bot ──

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (!Controle.enMarche() && !volumeTenu) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && !volumeTenu) {
                volumeTenu = true;
                volumeLong = false;
                principal.postDelayed(arretVolume, APPUI_LONG_MS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            principal.removeCallbacks(arretVolume);
            if (volumeTenu && !volumeLong) {
                AudioManager am = getSystemService(AudioManager.class);
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
            }
            volumeTenu = false;
            return true;
        }
        return true;
    }

    private void vibrer() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ── gestes ──

    private static float borne(double v, int max) { return (float) Math.max(0, Math.min(max - 1, v)); }

    /** Envoie un geste et attend sa fin ; false s'il a été annulé. */
    private boolean envoyer(GestureDescription g, long duree, float[] zone) {
        CountDownLatch fini = new CountDownLatch(1);
        boolean[] ok = {false};
        Runnable remettre = overlay != null ? overlay.laisserPasser(zone, duree) : null;
        try {
            boolean parti = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    fini.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) { fini.countDown(); }
            }, gestes);
            if (!parti) return false;
            if (!fini.await(duree + 3000, TimeUnit.MILLISECONDS)) return false;
            return ok[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Arret();
        } finally {
            if (remettre != null) remettre.run();
        }
    }

    private static float[] boite(List<float[]> pts) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] p : pts) {
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[0]);
            b[3] = Math.max(b[3], p[1]);
        }
        return b;
    }

    /** Touches les unes après les autres, par gestes de 20 traits au plus. */
    void toucher(int[][] points, int w, int h) { toucher(points, w, h, Config.ECART_MS); }

    void toucher(int[][] points, int w, int h, long ecart) {
        for (int k = 0; k < points.length; k += MAX_TRAITS) {
            Controle.verifier();
            GestureDescription.Builder b = new GestureDescription.Builder();
            List<float[]> pts = new ArrayList<>();
            int n = Math.min(MAX_TRAITS, points.length - k);
            for (int i = 0; i < n; i++) {
                float x = borne(points[k + i][0], w), y = borne(points[k + i][1], h);
                Path p = new Path();
                p.moveTo(x, y);
                b.addStroke(new GestureDescription.StrokeDescription(p, i * (Config.APPUI_MS + ecart), Config.APPUI_MS));
                pts.add(new float[]{x, y});
            }
            long duree = n * (Config.APPUI_MS + ecart);
            envoyer(b.build(), duree, boite(pts));
            if (n > 0) attendre(ecart);
        }
    }

    void glisser(double x1, double y1, double x2, double y2, long ms, long tenirMs, int w, int h) {
        Controle.verifier();
        Path p = new Path();
        p.moveTo(borne(x1, w), borne(y1, h));
        p.lineTo(borne(x2, w), borne(y2, h));
        GestureDescription.StrokeDescription trait = new GestureDescription.StrokeDescription(p, 0, Math.max(1, ms), tenirMs > 0);
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{borne(x1, w), borne(y1, h)});
        pts.add(new float[]{borne(x2, w), borne(y2, h)});
        envoyer(new GestureDescription.Builder().addStroke(trait).build(), ms, boite(pts));
        if (tenirMs > 0) {
            Path q = new Path();
            q.moveTo(borne(x2, w), borne(y2, h));
            envoyer(new GestureDescription.Builder().addStroke(trait.continueStroke(q, 0, tenirMs, false)).build(), tenirMs, boite(pts));
        }
    }

    /** Pincement : un doigt fixe au centre, l'autre qui s'en rapproche (dézoom) ou s'en éloigne. */
    boolean pincer(boolean dezoom, int w, int h) {
        Controle.verifier();
        float fx = borne(0.50 * w, w), fy = borne(0.45 * h, h);
        float loinX = borne(0.88 * w, w), presX = borne(0.56 * w, w);
        float dx = dezoom ? loinX : presX, ax = dezoom ? presX : loinX;
        Path fixe = new Path();
        fixe.moveTo(fx, fy);
        Path mobile = new Path();
        mobile.moveTo(dx, fy);
        mobile.lineTo(ax, fy);
        long d = Config.PINCEMENT_MS;
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(fixe, 0, d + 50))
                .addStroke(new GestureDescription.StrokeDescription(mobile, 0, d))
                .build();
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{fx, fy});
        pts.add(new float[]{dx, fy});
        pts.add(new float[]{ax, fy});
        return envoyer(g, d + 50, boite(pts));
    }

    private static void attendre(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Arret();
        }
    }

    /** Ramène Clash of Clans au premier plan. */
    boolean ouvrirJeu() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Config.JEU);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    void accueil() { performGlobalAction(GLOBAL_ACTION_HOME); }

    /** Plus de jeu ni de bot depuis 2 min : le service se coupe lui-même (rien ne reste allumé ;
     *  il sera rallumé au prochain lancement). */
    void couper() {
        Journal.log("Service d'accessibilité coupé (2 min sans le jeu ni le bot).");
        disableSelf();
    }

    void essaiLumiere(long ms) { if (overlay != null) overlay.essaiLumiere(ms); }

    void surPrincipal(Runnable r) { principal.post(r); }
}
