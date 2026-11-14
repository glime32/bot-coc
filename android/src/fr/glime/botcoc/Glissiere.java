package fr.glime.botcoc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Curseur de l'appli du PC : piste fine violette, poignée en étoile qui pivote de 45° tant
 *  qu'on la tient. */
final class Glissiere extends View {
    interface Ecouteur { void deplace(double v); }

    private final Paint fond = new Paint(Paint.ANTI_ALIAS_FLAG), plein = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint poignee = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path etoile = new Path();
    private final float dp;
    private double min = 0, max = 1, pas = 1, valeur = 0;
    private float angle = 0;
    private ValueAnimator animation;
    private Ecouteur ecouteur;

    Glissiere(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        etoile.moveTo(12, 0);
        etoile.cubicTo(12.75f, 6.6f, 17.4f, 11.25f, 24, 12);
        etoile.cubicTo(17.4f, 12.75f, 12.75f, 17.4f, 12, 24);
        etoile.cubicTo(11.25f, 17.4f, 6.6f, 12.75f, 0, 12);
        etoile.cubicTo(6.6f, 11.25f, 11.25f, 6.6f, 12, 0);
        etoile.close();
        fond.setColor(0xFF1C0029);
        majCouleurs();
    }

    void bornes(double min, double max, double pas) {
        this.min = min;
        this.max = max;
        this.pas = pas;
        invalidate();
    }

    void valeur(double v) {
        valeur = v;
        invalidate();
    }

    void ecouter(Ecouteur e) { ecouteur = e; }

    private float taille() { return 20 * dp; }

    private void majCouleurs() {
        plein.setColor(isEnabled() ? Overlay.VIOLET : 0xFF2B1838);
        poignee.setColor(isEnabled() ? Overlay.ETOILE : 0xFF4A3A55);
    }

    @Override
    public void setEnabled(boolean e) {
        super.setEnabled(e);
        majCouleurs();
        invalidate();
    }

    @Override
    protected void onMeasure(int lw, int lh) {
        setMeasuredDimension(MeasureSpec.getSize(lw), Math.round(28 * dp));
    }

    private double position() {
        double v = Math.max(min, Math.min(max, valeur));
        return max > min ? (v - min) / (max - min) : 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        float e = taille(), r = e / 2, cy = getHeight() / 2f, larg = getWidth() - e;
        c.drawRoundRect(new RectF(r, cy - 2 * dp, r + larg, cy + 2 * dp), 2 * dp, 2 * dp, fond);
        float x = (float) (position() * larg);
        c.drawRoundRect(new RectF(r, cy - 2 * dp, r + x, cy + 2 * dp), 2 * dp, 2 * dp, plein);
        c.save();
        c.translate(x + r, cy);
        c.rotate(angle);
        c.translate(-r, -r);
        c.scale(e / 24, e / 24);
        c.drawPath(etoile, poignee);
        c.restore();
    }

    private void tourner(float vers) {
        if (animation != null) animation.cancel();
        animation = ValueAnimator.ofFloat(angle, vers).setDuration(150);
        animation.setInterpolator(new DecelerateInterpolator(1.5f));
        animation.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            invalidate();
        });
        animation.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                tourner(45);
                suivre(ev.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                suivre(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tourner(0);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return false;
        }
    }

    private void suivre(float x) {
        float e = taille(), larg = getWidth() - e;
        double p = Math.max(0, Math.min(1, (x - e / 2) / larg));
        double v = min + p * (max - min);
        v = Math.round((v - min) / pas) * pas + min;
        v = Math.max(min, Math.min(max, v));
        if (v != valeur) {
            valeur = v;
            invalidate();
            if (ecouteur != null) ecouteur.deplace(v);
        }
    }
}
