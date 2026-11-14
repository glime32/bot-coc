package fr.glime.botcoc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** L'écran de l'appli, comme celle du PC : village, curseurs, butin, Lancer/Arrêter, compteurs,
 *  journal (ou état actuel). */
public final class MainActivity extends Activity {
    private static final int NOIR = 0xFF000000, CARTE = 0xFF0A0A0A, BORD = 0xFF3A0A55, VIOLET = 0xFF61008A;
    private static final int VIOLET_CLAIR = 0xFFC79BE0, TEXTE = 0xFFEEEEEE, DOUX = 0xFF9A8AA6;
    private static final int ROUGE = 0xFFFF6B6B, VERT = 0xFF7FD18B, GRIS_JOURNAL = 0xFFBDBDBD;

    private SharedPreferences prefs;
    private float dp;
    private final List<Runnable> majs = new ArrayList<>();
    private final List<View> reglables = new ArrayList<>();
    private TextView alerte, bouton, nombre, mot, petits, titreJournal, phase, detail, journal;
    private ScrollView defileJournal;
    private LinearLayout etatVue;
    private String phaseTexte = "Prêt", detailTexte = "Choisis les réglages puis lance le bot.";
    private final Controle.Ecouteur ecouteur = () -> runOnUiThread(this::maj);
    private final Journal.Ecouteur ecouteurJournal = l -> runOnUiThread(() -> {
        suivrePhase(l);
        majJournal();
    });

    private int px(float v) { return Math.round(v * dp); }

    private static GradientDrawable fond(int couleur, float rayon, int bord, int epaisseur) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(rayon);
        if (epaisseur > 0) g.setStroke(epaisseur, bord);
        return g;
    }

    private TextView texte(float sp, int couleur, boolean gras) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(couleur);
        if (gras) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView titre(String s) {
        TextView t = texte(12, DOUX, false);
        t.setText(s.toUpperCase(Locale.FRANCE));
        t.setLetterSpacing(0.08f);
        t.setPadding(px(10), 0, 0, 0);
        return t;
    }

    private LinearLayout.LayoutParams marge(int largeur, int hauteur, float haut) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(largeur, hauteur);
        l.topMargin = px(haut);
        return l;
    }

    /** Bouton arrondi qui reste enfoncé (village, ressources). */
    private TextView choix(String s, Supplier<Boolean> coche, Runnable clic) {
        TextView b = texte(14, 0xFFFFFFFF, false);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> {
            clic.run();
            toutMettreAJour();
        });
        majs.add(() -> {
            boolean c = coche.get();
            b.setBackground(fond(c ? VIOLET : NOIR, px(14), c ? VIOLET : BORD, Math.max(1, px(1))));
            b.setAlpha(b.isEnabled() ? 1f : 0.5f);
        });
        reglables.add(b);
        return b;
    }

    private Switch interrupteur(String s, Supplier<Boolean> coche, java.util.function.Consumer<Boolean> change) {
        Switch w = new Switch(this);
        w.setText(s);
        w.setTextColor(TEXTE);
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int[][] etats = {{android.R.attr.state_checked}, {}};
        w.setThumbTintList(new ColorStateList(etats, new int[]{0xFFFFFFFF, 0xFF9A8AA6}));
        w.setTrackTintList(new ColorStateList(etats, new int[]{VIOLET, 0xFF2B1838}));
        w.setChecked(coche.get());
        w.setOnCheckedChangeListener((v, c) -> {
            change.accept(c);
            toutMettreAJour();
        });
        return w;
    }

    /** Un réglage : titre, case où taper la valeur, curseur à étoile. */
    private LinearLayout reglage(String nom, Supplier<String> valeur, Supplier<Double> position, double min, double max,
                                 double pas, DoubleConsumer deplace, Supplier<Boolean> visible) {
        LinearLayout bloc = new LinearLayout(this);
        bloc.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ligne = new LinearLayout(this);
        ligne.setGravity(Gravity.CENTER_VERTICAL);
        ligne.setPadding(0, 0, px(10), 0);
        TextView t = titre(nom);
        ligne.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        EditText saisie = new EditText(this);
        saisie.setTextColor(0xFFFFFFFF);
        saisie.setTypeface(Typeface.DEFAULT_BOLD);
        saisie.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        saisie.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        saisie.setSingleLine(true);
        saisie.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        saisie.setImeOptions(EditorInfo.IME_ACTION_DONE);
        saisie.setPadding(px(8), px(6), px(8), px(6));
        saisie.setSelectAllOnFocus(true);
        saisie.setOnFocusChangeListener((v, f) -> saisie.setBackground(fond(CARTE, px(6), f ? 0xFFD7A6F0 : VIOLET, Math.max(1, px(1)))));
        saisie.setBackground(fond(CARTE, px(6), VIOLET, Math.max(1, px(1))));
        Runnable valider = () -> {
            String s = saisie.getText().toString().replace(",", ".").replaceAll("\\s", "");
            try {
                deplace.accept(Math.max(min, Double.parseDouble(s)));
            } catch (NumberFormatException ignored) {
            }
            saisie.clearFocus();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(saisie.getWindowToken(), 0);
            toutMettreAJour();
        };
        saisie.setOnEditorActionListener((v, a, e) -> {
            valider.run();
            return true;
        });
        ligne.addView(saisie, new LinearLayout.LayoutParams(px(96), -2));
        bloc.addView(ligne);
        Glissiere g = new Glissiere(this);
        g.bornes(min, max, pas);
        g.ecouter(v -> {
            deplace.accept(v);
            toutMettreAJour();
        });
        bloc.addView(g, marge(-1, -2, 12));
        reglables.add(saisie);
        reglables.add(g);
        majs.add(() -> {
            bloc.setVisibility(visible.get() ? View.VISIBLE : View.GONE);
            if (!saisie.hasFocus()) saisie.setText(valeur.get());
            g.valeur(position.get());
        });
        return bloc;
    }

    private static String millions(double v) { return String.format(Locale.FRANCE, "%.1f M", v); }

    private boolean ouvriers() { return prefs.getString("village", "auto").equals("ouvriers"); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = Reglages.prefs(this);
        Config.opencv();
        Fichiers.installer(this);
        getWindow().setStatusBarColor(NOIR);
        getWindow().setNavigationBarColor(NOIR);

        ScrollView defile = new ScrollView(this);
        defile.setBackgroundColor(NOIR);
        defile.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(20), px(16), px(16));
        defile.addView(col);

        alerte = texte(13, ROUGE, false);
        alerte.setPadding(px(10), 0, px(10), px(12));
        alerte.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(alerte);

        col.addView(titre("Village"));
        col.addView(choix("Auto", () -> prefs.getString("village", "auto").equals("auto"),
                () -> prefs.edit().putString("village", "auto").apply()), marge(-1, px(40), 8));
        LinearLayout villages = new LinearLayout(this);
        villages.addView(choix("Principal", () -> prefs.getString("village", "auto").equals("principal"),
                () -> prefs.edit().putString("village", "principal").apply()), new LinearLayout.LayoutParams(0, px(40), 1));
        View e1 = new View(this);
        villages.addView(e1, new LinearLayout.LayoutParams(px(6), 1));
        villages.addView(choix("Ouvriers", this::ouvriers,
                () -> prefs.edit().putString("village", "ouvriers").apply()), new LinearLayout.LayoutParams(0, px(40), 1));
        col.addView(villages, marge(-1, -2, 6));

        col.addView(reglage("Attaques", () -> prefs.getInt("attaques", 14) == 0 ? "sans fin" : String.valueOf(prefs.getInt("attaques", 14)),
                () -> (double) prefs.getInt("attaques", 14), 0, 60, 1,
                v -> prefs.edit().putInt("attaques", (int) Math.round(v)).apply(), () -> true), marge(-1, -2, 16));
        col.addView(reglage("Durée max d'un combat", () -> prefs.getInt("duree", 29) == 0 ? "sans limite" : prefs.getInt("duree", 29) + " s",
                () -> (double) prefs.getInt("duree", 29), 0, 180, 1,
                v -> prefs.edit().putInt("duree", (int) Math.round(v)).apply(), () -> !ouvriers()), marge(-1, -2, 14));
        col.addView(reglage("Capituler sans destruction depuis", () -> prefs.getInt("fige", 6) == 0 ? "jamais" : prefs.getInt("fige", 6) + " s",
                () -> (double) prefs.getInt("fige", 6), 0, 60, 1,
                v -> prefs.edit().putInt("fige", (int) Math.round(v)).apply(), () -> !ouvriers()), marge(-1, -2, 14));

        LinearLayout butin = new LinearLayout(this);
        butin.setGravity(Gravity.CENTER_VERTICAL);
        butin.addView(titre("Butin minimum"), new LinearLayout.LayoutParams(0, -2, 1));
        Switch parRessource = interrupteur("Par ressource", () -> prefs.getBoolean("parRessource", false),
                c -> prefs.edit().putBoolean("parRessource", c).apply());
        butin.addView(parRessource);
        reglables.add(parRessource);
        majs.add(() -> butin.setVisibility(ouvriers() ? View.GONE : View.VISIBLE));
        col.addView(butin, marge(-1, -2, 14));

        col.addView(reglage("Total", () -> millions(prefs.getFloat("butin", 2.2f)), () -> (double) prefs.getFloat("butin", 2.2f), 0, 6, 0.1,
                v -> prefs.edit().putFloat("butin", (float) (Math.round(v * 10) / 10.0)).apply(),
                () -> !ouvriers() && !prefs.getBoolean("parRessource", false)), marge(-1, -2, 8));
        LinearLayout ressources = new LinearLayout(this);
        String[][] r = {{"Or", "avecOr", "1"}, {"Élixir", "avecElixir", "0"}, {"Noir", "avecNoir", "1"}};
        for (int i = 0; i < r.length; i++) {
            String cle = r[i][1];
            boolean defaut = r[i][2].equals("1");
            if (i > 0) ressources.addView(new View(this), new LinearLayout.LayoutParams(px(6), 1));
            ressources.addView(choix(r[i][0], () -> prefs.getBoolean(cle, defaut),
                    () -> prefs.edit().putBoolean(cle, !prefs.getBoolean(cle, defaut)).apply()), new LinearLayout.LayoutParams(0, px(40), 1));
        }
        majs.add(() -> ressources.setVisibility(!ouvriers() && prefs.getBoolean("parRessource", false) ? View.VISIBLE : View.GONE));
        col.addView(ressources, marge(-1, -2, 8));
        col.addView(reglage("Or", () -> millions(prefs.getFloat("butinOr", 1f)), () -> (double) prefs.getFloat("butinOr", 1f), 0, 3, 0.1,
                v -> prefs.edit().putFloat("butinOr", (float) (Math.round(v * 10) / 10.0)).apply(),
                () -> !ouvriers() && prefs.getBoolean("parRessource", false) && prefs.getBoolean("avecOr", true)), marge(-1, -2, 12));
        col.addView(reglage("Élixir", () -> millions(prefs.getFloat("butinElixir", 1f)), () -> (double) prefs.getFloat("butinElixir", 1f), 0, 3, 0.1,
                v -> prefs.edit().putFloat("butinElixir", (float) (Math.round(v * 10) / 10.0)).apply(),
                () -> !ouvriers() && prefs.getBoolean("parRessource", false) && prefs.getBoolean("avecElixir", false)), marge(-1, -2, 12));
        col.addView(reglage("Noir", () -> Chiffres.espaces(prefs.getInt("butinNoir", 8000)), () -> (double) prefs.getInt("butinNoir", 8000), 0, 20000, 500,
                v -> prefs.edit().putInt("butinNoir", (int) Math.round(v)).apply(),
                () -> !ouvriers() && prefs.getBoolean("parRessource", false) && prefs.getBoolean("avecNoir", true)), marge(-1, -2, 12));

        bouton = texte(16, 0xFFFFFFFF, true);
        bouton.setGravity(Gravity.CENTER);
        bouton.setOnClickListener(v -> appuyer());
        col.addView(bouton, marge(-1, px(48), 22));

        LinearLayout compteurs = new LinearLayout(this);
        compteurs.setOrientation(LinearLayout.VERTICAL);
        compteurs.setGravity(Gravity.CENTER_HORIZONTAL);
        compteurs.setPadding(0, px(10), 0, px(10));
        compteurs.setBackground(fond(CARTE, px(6), BORD, Math.max(1, px(1))));
        nombre = texte(40, 0xFFFFFFFF, true);
        mot = texte(14, VIOLET_CLAIR, false);
        petits = texte(12, DOUX, false);
        petits.setPadding(0, px(4), 0, 0);
        compteurs.addView(nombre);
        compteurs.addView(mot);
        compteurs.addView(petits);
        col.addView(compteurs, marge(-1, -2, 10));

        LinearLayout tete = new LinearLayout(this);
        tete.setGravity(Gravity.CENTER_VERTICAL);
        titreJournal = titre("État");
        tete.addView(titreJournal, new LinearLayout.LayoutParams(0, -2, 1));
        tete.addView(interrupteur("Historique", () -> prefs.getBoolean("historique", false),
                c -> prefs.edit().putBoolean("historique", c).apply()));
        col.addView(tete, marge(-1, -2, 16));

        LinearLayout boite = new LinearLayout(this);
        boite.setOrientation(LinearLayout.VERTICAL);
        boite.setBackground(fond(NOIR, px(6), BORD, Math.max(1, px(1))));
        boite.setPadding(px(8), px(8), px(8), px(8));
        defileJournal = new ScrollView(this);
        journal = texte(11, GRIS_JOURNAL, false);
        journal.setTypeface(Typeface.MONOSPACE);
        defileJournal.addView(journal);
        boite.addView(defileJournal, new LinearLayout.LayoutParams(-1, px(300)));
        etatVue = new LinearLayout(this);
        etatVue.setOrientation(LinearLayout.VERTICAL);
        etatVue.setGravity(Gravity.CENTER);
        phase = texte(22, 0xFFFFFFFF, true);
        phase.setGravity(Gravity.CENTER);
        detail = texte(13, DOUX, false);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(px(8), px(10), px(8), 0);
        etatVue.addView(phase);
        etatVue.addView(detail);
        boite.addView(etatVue, new LinearLayout.LayoutParams(-1, px(180)));
        col.addView(boite, marge(-1, -2, 6));

        setContentView(defile);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Controle.ecouter(ecouteur);
        Journal.ecouter(ecouteurJournal);
        for (String l : Journal.dernieres(40)) suivrePhase(l);
        maj();
        majJournal();
    }

    @Override
    protected void onPause() {
        Controle.oublier(ecouteur);
        Journal.oublier(ecouteurJournal);
        super.onPause();
    }

    private boolean accessibiliteActive() { return Acces.assurer(this); }

    private void toutMettreAJour() {
        boolean libre = !Controle.enMarche();
        for (View v : reglables) v.setEnabled(libre);
        for (Runnable r : majs) r.run();
    }

    private void maj() {
        boolean marche = Controle.enMarche();
        boolean a = accessibiliteActive();
        alerte.setVisibility(a ? View.GONE : View.VISIBLE);
        alerte.setText("Le service d'accessibilité Bot CoC est coupé : touche ici pour l'allumer.");
        bouton.setText(marche ? "■  Arrêter" : "▶  Lancer");
        bouton.setTextColor(marche ? VIOLET_CLAIR : 0xFFFFFFFF);
        bouton.setBackground(fond(marche ? NOIR : VIOLET, px(14), VIOLET, marche ? px(2) : 0));
        int n = marche ? Controle.attaque : Controle.faites;
        nombre.setText(String.valueOf(n));
        mot.setText(n > 1 ? "attaques" : "attaque");
        int b = Controle.blocages;
        petits.setText(Controle.suivants + " Suivant · " + b + (b > 1 ? " blocages" : " blocage"));
        toutMettreAJour();
        majJournal();
    }

    private static int couleurDe(String l) {
        if (l.matches(".*(Bloqué|impossible|Erreur|Error).*")) return ROUGE;
        if (l.matches(".*(Fin :|vérifié : tout est posé|Village principal :|Base des ouvriers).*")) return VERT;
        if (l.matches(".*(Attaque n°|→).*")) return VIOLET_CLAIR;
        return GRIS_JOURNAL;
    }

    /** L'état actuel en une phrase, d'après la dernière ligne du journal (comme sur le PC). */
    private void suivrePhase(String l) {
        String t = l.replaceFirst("^\\d\\d:\\d\\d:\\d\\d\\s+", "").trim();
        if (t.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Attaque n°(\\d+)").matcher(t);
        if (m.find()) phaseTexte = "Attaque " + m.group(1) + " : recherche d'un village";
        else if (t.startsWith("Village détecté")) {
            String v = t.replace("Village détecté : ", "");
            phaseTexte = v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1);
        } else if (t.contains("→ suivant")) phaseTexte = "Village suivant (pas assez de butin)";
        else if (t.startsWith("butin")) phaseTexte = "Village trouvé";
        else if (t.matches("^(barre|armée|vue haut|vue bas :.*touches|il reste|vérifié).*")) phaseTexte = "Pose des troupes";
        else if (t.matches(".*(sorts sur|pas de troupes visibles).*")) phaseTexte = "Sorts lancés, combat en cours";
        else if (t.contains("compétences")) phaseTexte = "Combat en cours";
        else if (t.contains("vitesse ×4")) phaseTexte = "Combat en cours (×4)";
        else if (t.matches(".*(capituler|→ confirmer).*")) phaseTexte = "Capitulation";
        else if (t.matches(".*(→ rentrer|déjà revenu).*")) phaseTexte = "Retour au village";
        else if (t.startsWith("Bloqué")) phaseTexte = "Bloqué, je réessaie";
        else if (t.contains("jeu caché")) phaseTexte = "En pause : le jeu est caché";
        else if (t.contains("premier plan")) phaseTexte = "Retour au jeu";
        else if (t.startsWith("Fin :")) phaseTexte = "Terminé";
        else if (t.startsWith("Arrêt demandé")) phaseTexte = "Arrêté";
        else if (t.matches(".*→ (combat|trouver|attaquer).*")) phaseTexte = "Recherche d'un village";
        detailTexte = t;
    }

    private void majJournal() {
        boolean historique = prefs.getBoolean("historique", false);
        titreJournal.setText((historique ? "Journal" : "État").toUpperCase(Locale.FRANCE));
        defileJournal.setVisibility(historique ? View.VISIBLE : View.GONE);
        etatVue.setVisibility(historique ? View.GONE : View.VISIBLE);
        phase.setText(phaseTexte);
        phase.setTextColor(phaseTexte.contains("Bloqué") ? ROUGE : phaseTexte.equals("Terminé") ? VERT : 0xFFFFFFFF);
        detail.setText(detailTexte);
        if (!historique) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String l : Journal.dernieres(300)) {
            if (sb.length() > 0) sb.append('\n');
            int d = sb.length();
            sb.append(l);
            sb.setSpan(new ForegroundColorSpan(couleurDe(l)), d, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        journal.setText(sb);
        defileJournal.post(() -> defileJournal.fullScroll(View.FOCUS_DOWN));
    }

    private void appuyer() {
        if (Controle.enMarche()) {
            Controle.arreter();
            return;
        }
        if (!accessibiliteActive()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        phaseTexte = "Démarrage";
        detailTexte = "";
        Intent jeu = getPackageManager().getLaunchIntentForPackage(Config.JEU);
        if (jeu != null) startActivity(jeu);
        Demarrage.lancer(this, null, null);
    }
}
