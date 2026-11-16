package lbpjs.teavm;

/** Math.fma for the TeaVM build. JavaScript has no fused multiply-add. */
public final class Fma
{
    private Fma() { }

    public static float fma(float a, float b, float c) { return (float) ((double) a * (double) b + (double) c); }

    public static double fma(double a, double b, double c) { return a * b + c; }
}
