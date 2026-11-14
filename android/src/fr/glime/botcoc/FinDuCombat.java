package fr.glime.botcoc;

/** Le bouton rouge a disparu : on ne touche plus rien. */
final class FinDuCombat extends RuntimeException {
    FinDuCombat() { super("fin du combat"); }
}
