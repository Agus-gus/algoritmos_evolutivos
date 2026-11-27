package uy.fing.ae;

/** Asignación fraccional IDW manzana -> contenedores y generación por contenedor y turno. */
public final class Generation {
    private Generation() {}

    /** @return dq[h][i] = α_h · g · ω_b(i) · P_i   (kg por turno) */
    public static double[][] dq(double[] cx, double[] cy, double[] mx, double[] my, double[] pop,
                                double R, double beta, double g, double[] alpha, double[] omega) {
        int n = cx.length;
        double[] P = new double[n];
        int[] idx = new int[n];
        double[] w = new double[n];
        double R2 = R * R;
        // ponytail: O(M·N) por fuerza bruta (~4e8 operaciones, segundos). Si molesta: grilla espacial de lado R.
        for (int m = 0; m < mx.length; m++) {
            int c = 0;
            double sum = 0, best = Double.MAX_VALUE;
            int nearest = -1;
            for (int i = 0; i < n; i++) {
                double dx = mx[m] - cx[i], dy = my[m] - cy[i];
                double d2 = dx * dx + dy * dy;
                if (d2 < best) { best = d2; nearest = i; }
                if (d2 <= R2) {
                    double d = Math.max(Math.sqrt(d2), 1.0);
                    idx[c] = i; w[c] = 1.0 / Math.pow(d, beta); sum += w[c]; c++;
                }
            }
            if (c == 0) P[nearest] += pop[m];                     // manzana aislada: al más cercano
            else for (int k = 0; k < c; k++) P[idx[k]] += pop[m] * w[k] / sum;
        }
        double[][] dq = new double[alpha.length][n];
        for (int h = 0; h < alpha.length; h++)
            for (int i = 0; i < n; i++) dq[h][i] = alpha[h] * g * omega[i] * P[i];
        return dq;
    }
}
