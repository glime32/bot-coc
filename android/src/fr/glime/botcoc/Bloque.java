package fr.glime.botcoc;

/** L'écran attendu n'est pas venu : le cycle repart de zéro. */
final class Bloque extends RuntimeException {
    final transient Ecran ecran;

    Bloque(String message, Ecran ecran) {
        super(message);
        this.ecran = ecran;
    }

    Bloque(String message) { this(message, null); }
}
