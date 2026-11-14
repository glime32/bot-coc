package fr.glime.botcoc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** La pastille en haut au centre (étoile, « 3 / 14 », point d'état) et le panneau qui s'ouvre
 *  quand on la touche : bouton Lancer/Arrêter, compteurs, 5 dernières lignes du journal. */
final class Overlay {
    static final int NOIR = 0xFF000000, BORD = 0xFF3A0A55, ETOILE = 0xFFD7A6F0, VIOLET = 0xFF61008A;
    static final int TEXTE = 0xFFEEEEEE, DOUX = 0xFF9A8AA6, JOURNAL = 0xFFC8A6DD, TRAIT = 0xFF2B1838;
    static final int VERT = 0xFF7FD18B, ORANGE = 0xFFE0A83A, ROUGE = 0xFFFF6B6B, GRIS = 0xFF5F5068, MAUVE = 0xFFC79BE0;
    private static final long FERMETURE_MS = 6000;

    private final BotService service;
    private final WindowManager wm;
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final float dp;

    private LinearLayout pastille;
    private TextView compte;
    private View point;
    private WindowManager.LayoutParams lpPastille;
    private LinearLayout panneau;
    private WindowManager.LayoutParams lpPanneau;
    private TextView gros, details, bouton, journal;
    private boolean jeuVisible = false, panneauOuvert = false, pastilleAffichee = false, panneauAffiche = false;
    private long fermeDehors = 0;
    private volatile long finGeste = 0;
    private final Runnable fermer = this::fermerPanneau;
    private static final long PARTAGE_INUTILE_MS = 120_000;
    private final Runnable fermerPartage = this::fermerPartageInutile;
    private boolean coupurePrevue = false;
    static volatile boolean panneauVisible = false;
    /** Luminosité demandée par la pastille pendant le bot (choix « écran au minimum »). */
    private static final float LUMIERE_BOT = 0.01f;
    private volatile long essaiLumiereJusqua = 0;
    private final Controle.Ecouteur ecouteurControle = () -> principal.post(this::maj);
    private final Journal.Ecouteur ecouteurJournal = l -> principal.post(this::majJournal);

    Overlay(BotService service) {
        this.service = service;
        this.wm = service.getSystemService(WindowManager.class);
        this.prefs = Reglages.prefs(service);
        this.dp = service.getResources().getDisplayMetrics().density;
        construire();
        Controle.ecouter(ecouteurControle);
        Journal.ecouter(ecouteurJournal);
        maj();
        principal.postDelayed(this::verifierJeu, 1000);
    }

    /** Le jeu était peut-être déjà ouvert avant le service : on regarde la fenêtre active. */
    private void verifierJeu() {
        jeuVisible = service.jeuAuPremierPlan();
        majVisibilite();
    }

    private int px(float v) { return Math.round(v * dp); }

    private static GradientDrawable fond(int couleur, float rayon, int bord, float epaisseur) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(rayon);
        if (epaisseur > 0) g.setStroke(Math.max(1, Math.round(epaisseur)), bord);
        return g;
    }

    /** L'étoile à quatre branches de l'appli du PC. */
    static final class Etoile extends View {
        private final Paint peinture = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chemin = new Path();

        Etoile(Context c, int couleur) {
            super(c);
            peinture.setColor(couleur);
            chemin.moveTo(12, 0);
            chemin.cubicTo(12.75f, 6.6f, 17.4f, 11.25f, 24, 12);
            chemin.cubicTo(17.4f, 12.75f, 12.75f, 17.4f, 12, 24);
            chemin.cubicTo(11.25f, 17.4f, 6.6f, 12.75f, 0, 12);
            chemin.cubicTo(6.6f, 11.25f, 11.25f, 6.6f, 12, 0);
            chemin.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * k) / 2, (getHeight() - 24 * k) / 2);
            c.scale(k, k);
            c.drawPath(chemin, peinture);
            c.restore();
        }
    }

    private TextView texte(float sp, int couleur, boolean gras) {
        TextView t = new TextView(service);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(couleur);
        t.setIncludeFontPadding(false);
        if (gras) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void construire() {
        pastille = new LinearLayout(service);
        pastille.setOrientation(LinearLayout.HORIZONTAL);
        pastille.setGravity(Gravity.CENTER_VERTICAL);
        pastille.setPadding(px(11), px(6), px(11), px(6));
        pastille.setBackground(fond(NOIR, px(99), BORD, dp));
        pastille.addView(new Etoile(service, ETOILE), new LinearLayout.LayoutParams(px(15), px(15)));
        compte = texte(14, TEXTE, true);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(-2, -2);
        lc.leftMargin = px(7);
        lc.rightMargin = px(7);
        pastille.addView(compte, lc);
        point = new View(service);
        point.setBackground(fond(GRIS, px(4), 0, 0));
        pastille.addView(point, new LinearLayout.LayoutParams(px(8), px(8)));
        pastille.setOnTouchListener(new Glisseur());

        lpPastille = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lpPastille.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lpPastille.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lpPastille.x = prefs.getInt("pastilleX", 0);
        lpPastille.y = prefs.getInt("pastilleY", px(6));
        lpPastille.setTitle("Bot CoC");

        panneau = new LinearLayout(service);
        panneau.setOrientation(LinearLayout.VERTICAL);
        panneau.setPadding(px(12), px(12), px(12), px(12));
        panneau.setBackground(fond(NOIR, px(16), BORD, dp));
        LinearLayout tete = new LinearLayout(service);
        tete.setGravity(Gravity.CENTER_VERTICAL);
        tete.addView(new Etoile(service, ETOILE), new LinearLayout.LayoutParams(px(13), px(13)));
        TextView titre = texte(13, TEXTE, true);
        titre.setText("Bot CoC");
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.leftMargin = px(8);
        tete.addView(titre, lt);
        panneau.addView(tete);
        LinearLayout ligne = new LinearLayout(service);
        ligne.setGravity(Gravity.BOTTOM);
        gros = texte(30, TEXTE, true);
        details = texte(12, DOUX, false);
        LinearLayout.LayoutParams ld = new LinearLayout.LayoutParams(-2, -2);
        ld.leftMargin = px(5);
        ld.bottomMargin = px(3);
        ligne.addView(gros);
        ligne.addView(details, ld);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(10);
        panneau.addView(ligne, ll);
        bouton = texte(13, Color.WHITE, true);
        bouton.setGravity(Gravity.CENTER);
        bouton.setLetterSpacing(0.02f);
        bouton.setBackground(fond(VIOLET, px(14), 0, 0));
        bouton.setOnClickListener(v -> appuyer());
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, px(34));
        lb.topMargin = px(10);
        lb.bottomMargin = px(8);
        panneau.addView(bouton, lb);
        View trait = new View(service);
        trait.setBackgroundColor(TRAIT);
        panneau.addView(trait, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(dp))));
        journal = texte(11, JOURNAL, false);
        journal.setTypeface(Typeface.MONOSPACE);
        journal.setLineSpacing(0, 1.2f);
        journal.setMaxLines(5);
        journal.setSingleLine(false);
        LinearLayout.LayoutParams lj = new LinearLayout.LayoutParams(-1, -2);
        lj.topMargin = px(6);
        panneau.addView(journal, lj);
        panneau.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                if (System.currentTimeMillis() > finGeste + 250) {
                    fermerPanneau();
                    fermeDehors = System.currentTimeMillis();
                }
                return true;
            }
            relancerMinuterie();
            return false;
        });

        lpPanneau = new WindowManager.LayoutParams(px(300), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lpPanneau.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lpPanneau.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lpPanneau.setTitle("Bot CoC panneau");
    }

    /** Glisser la pastille la déplace ; la toucher ouvre ou ferme le panneau. */
    private final class Glisseur implements View.OnTouchListener {
        float x0, y0;
        int px0, py0;
        boolean glisse;
        final int seuil = ViewConfiguration.get(service).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    px0 = lpPastille.x;
                    py0 = lpPastille.y;
                    glisse = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!glisse && Math.hypot(dx, dy) > seuil) glisse = true;
                    if (glisse) {
                        lpPastille.x = px0 + Math.round(dx);
                        lpPastille.y = Math.max(0, py0 + Math.round(dy));
                        if (pastilleAffichee) wm.updateViewLayout(pastille, lpPastille);
                        if (panneauAffiche) placerPanneau();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (glisse) {
                        prefs.edit().putInt("pastilleX", lpPastille.x).putInt("pastilleY", lpPastille.y).apply();
                    } else if (panneauOuvert) {
                        fermerPanneau();
                    } else if (System.currentTimeMillis() - fermeDehors > 400) {
                        ouvrirPanneau();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void placerPanneau() {
        lpPanneau.x = lpPastille.x;
        lpPanneau.y = lpPastille.y + Math.max(pastille.getHeight(), px(30)) + px(6);
        if (panneauAffiche) wm.updateViewLayout(panneau, lpPanneau);
    }

    private void ouvrirPanneau() {
        panneauOuvert = true;
        majVisibilite();
        relancerMinuterie();
    }

    private void fermerPanneau() {
        panneauOuvert = false;
        principal.removeCallbacks(fermer);
        majVisibilite();
    }

    private void relancerMinuterie() {
        principal.removeCallbacks(fermer);
        principal.postDelayed(fermer, FERMETURE_MS);
    }

    private void appuyer() {
        relancerMinuterie();
        if (Controle.enMarche()) {
            Controle.arreter();
        } else if (CaptureService.actif()) {
            Controle.lancer(service);
        } else {
            Intent i = new Intent(service, ConsentActivity.class).putExtra("lancer", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(i);
        }
    }

    /** Ni jeu à l'écran ni bot depuis 2 min : on rend le partage d'écran et on coupe le service
     *  d'accessibilité (tous deux se rallument sans question au prochain lancement). */
    private void fermerPartageInutile() {
        coupurePrevue = false;
        if (Controle.enMarche() || jeuVisible) return;
        if (CaptureService.actif()) {
            service.startService(new Intent(service, CaptureService.class).setAction(CaptureService.FERMER));
        }
        service.couper();
    }

    /** Essai depuis le PC : écran au minimum pendant `ms`. */
    void essaiLumiere(long ms) {
        principal.post(() -> {
            essaiLumiereJusqua = System.currentTimeMillis() + ms;
            majVisibilite();
            principal.postDelayed(this::majVisibilite, ms + 50);
        });
    }

    void jeuAuPremierPlan(boolean visible) {
        principal.post(() -> {
            jeuVisible = visible;
            majVisibilite();
        });
    }

    private void majVisibilite() {
        if (!jeuVisible && !Controle.enMarche()) jeuVisible = service.jeuAuPremierPlan();
        boolean montrer = jeuVisible || Controle.enMarche();
        try {
            if (montrer && !pastilleAffichee) {
                wm.addView(pastille, lpPastille);
                pastilleAffichee = true;
            } else if (!montrer && pastilleAffichee) {
                wm.removeView(pastille);
                pastilleAffichee = false;
            }
            if (montrer) {
                principal.removeCallbacks(fermerPartage);
                coupurePrevue = false;
            } else if (!coupurePrevue) {
                principal.postDelayed(fermerPartage, PARTAGE_INUTILE_MS);
                coupurePrevue = true;
            }
            boolean panneauVoulu = montrer && panneauOuvert;
            panneauVisible = panneauVoulu;
            if (panneauVoulu && !panneauAffiche) {
                placerPanneau();
                wm.addView(panneau, lpPanneau);
                panneauAffiche = true;
                majJournal();
            } else if (!panneauVoulu && panneauAffiche) {
                wm.removeView(panneau);
                panneauAffiche = false;
            }
            int f = lpPastille.flags;
            if (Controle.enMarche()) f |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            else f &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            // écran au minimum tant que le bot joue ; normal à l'arrêt, panneau ouvert ou jeu caché
            boolean sombre = (Controle.enMarche() && jeuVisible && !panneauOuvert) || System.currentTimeMillis() < essaiLumiereJusqua;
            float lumiere = sombre ? LUMIERE_BOT : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            if (f != lpPastille.flags || lumiere != lpPastille.screenBrightness) {
                lpPastille.flags = f;
                lpPastille.screenBrightness = lumiere;
                if (pastilleAffichee) wm.updateViewLayout(pastille, lpPastille);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void maj() {
        boolean marche = Controle.enMarche();
        int n = marche ? Controle.attaque : Controle.faites;
        String total = Controle.total > 0 ? String.valueOf(Controle.total) : marche ? "∞" : String.valueOf(prefs.getInt("attaques", 14));
        compte.setText(n + " / " + total);
        int couleur;
        switch (Controle.etat) {
            case MARCHE: couleur = VERT; break;
            case PAUSE: couleur = ORANGE; break;
            case BLOQUE: couleur = ROUGE; break;
            default: couleur = GRIS;
        }
        ((GradientDrawable) point.getBackground()).setColor(couleur);
        gros.setText(String.valueOf(n));
        int b = Controle.blocages;
        details.setText("/ " + total + " attaques · " + Controle.suivants + " Suivant · " + b + " blocage" + (b > 1 ? "s" : ""));
        bouton.setText(marche ? "Arrêter" : "Lancer");
        majVisibilite();
    }

    static int couleurDe(String l) {
        if (l.matches(".*(Bloqué|impossible|Erreur|Error).*")) return ROUGE;
        if (l.matches(".*(Fin :|vérifié : tout est posé|Village principal :|Base des ouvriers).*")) return VERT;
        if (l.matches(".*(Attaque n°|→).*")) return MAUVE;
        return JOURNAL;
    }

    private void majJournal() {
        if (!panneauAffiche) return;
        List<String> lignes = Journal.dernieres(5);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String l : lignes) {
            String t = l.replaceFirst("^\\d\\d:\\d\\d:\\d\\d\\s+", "").trim();
            if (sb.length() > 0) sb.append('\n');
            int debut = sb.length();
            sb.append(t);
            sb.setSpan(new ForegroundColorSpan(couleurDe(t)), debut, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        journal.setText(sb);
    }

    /** Pendant un geste du bot qui passe sur la pastille ou le panneau : ils laissent passer les
     *  touches. Renvoie de quoi les rendre à nouveau touchables. */
    Runnable laisserPasser(float[] zone, long dureeMs) {
        finGeste = System.currentTimeMillis() + dureeMs;
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        boolean[] change = {false};
        CountDownLatch fait = new CountDownLatch(1);
        principal.post(() -> {
            try {
                change[0] = rendreTraversable(true, zone);
            } finally {
                fait.countDown();
            }
        });
        try {
            fait.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Arret();
        }
        if (!change[0]) return null;
        return () -> principal.post(() -> rendreTraversable(false, null));
    }

    private boolean touche(View v, float[] z) {
        int[] o = new int[2];
        v.getLocationOnScreen(o);
        int m = px(8);
        return !(z[2] < o[0] - m || z[0] > o[0] + v.getWidth() + m || z[3] < o[1] - m || z[1] > o[1] + v.getHeight() + m);
    }

    private boolean rendreTraversable(boolean oui, float[] zone) {
        boolean change = false;
        int flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            if (pastilleAffichee && (!oui || touche(pastille, zone))) {
                int f = oui ? lpPastille.flags | flag : lpPastille.flags & ~flag;
                if (f != lpPastille.flags) {
                    lpPastille.flags = f;
                    wm.updateViewLayout(pastille, lpPastille);
                    change = true;
                }
            }
            if (panneauAffiche && (!oui || touche(panneau, zone))) {
                int f = oui ? lpPanneau.flags | flag : lpPanneau.flags & ~flag;
                if (f != lpPanneau.flags) {
                    lpPanneau.flags = f;
                    wm.updateViewLayout(panneau, lpPanneau);
                    change = true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return change;
    }

    void retirer() {
        Controle.oublier(ecouteurControle);
        Journal.oublier(ecouteurJournal);
        principal.post(() -> {
            try {
                if (pastilleAffichee) wm.removeView(pastille);
                if (panneauAffiche) wm.removeView(panneau);
            } catch (RuntimeException ignored) {
            }
            pastilleAffichee = panneauAffiche = false;
        });
    }
}
