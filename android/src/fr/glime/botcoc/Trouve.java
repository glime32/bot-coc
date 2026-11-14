package fr.glime.botcoc;

/** Un bouton trouvé : centre et boîte en pixels de l'écran. */
final class Trouve {
    final String nom;
    final int x, y;
    final int[] boite;      // x, y, largeur, hauteur
    final double score;
    boolean valide = true;  // false : bon score en gris, mais couleur ou contraste différents

    Trouve(String nom, int x, int y, int[] boite, double score) {
        this.nom = nom;
        this.x = x;
        this.y = y;
        this.boite = boite;
        this.score = score;
    }

    int[] point() { return new int[]{x, y}; }
}
