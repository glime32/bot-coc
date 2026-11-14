package fr.glime.botcoc;

import java.util.ArrayList;
import java.util.List;

/** Une carte de la barre des troupes. */
final class Carte {
    int x0, y0, x1, y1;
    double couleur0 = 0.0;           // taux de couleur au début du combat
    Integer nombre;                  // compteur « x50 » lu au début ; null = unité unique
    String sort;                     // « precipitation », « rage »… ; null = pas un sort connu
    boolean heros = false;
    String connue;                   // modèle de templates/cartes/ qui l'a reconnue
    final List<int[]> points = new ArrayList<>();   // où elle a été posée

    Carte(int x0, int y0, int x1, int y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** Où toucher la carte : haut-centre, pour éviter les icônes du bas. */
    int[] point() { return new int[]{(x0 + x1) / 2, y0 + (y1 - y0) * 2 / 5}; }

    String decrire() {
        if (sort != null) return sort + " x" + (nombre != null ? nombre : "?");
        if (nombre != null && nombre != 0) return "x" + nombre;
        return heros ? "héros" : "unité";
    }
}
