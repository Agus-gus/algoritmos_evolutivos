package uy.fing.ae;

import java.util.Arrays;

/** Dinámica de llenado + decodificador (reparación) + evaluación de f1, f2 y violación v de R1. */
public final class Simulator {
    private final Instance in;
    /** false: q(t+1)=0 si se recolecta (fórmula del informe). true: se vacía y luego suma la generación del turno. */
    public boolean generateAfterCollect = false;

    public Simulator(Instance in) { this.in = in; }

    public static final class State {
        public final double[] q;   // kg
        public final int[] age;    // turnos consecutivos lleno (a_i)
        public State(int n) { q = new double[n]; age = new int[n]; }
        public State copy() {
            State s = new State(q.length);
            System.arraycopy(q, 0, s.q, 0, q.length);
            System.arraycopy(age, 0, s.age, 0, age.length);
            return s;
        }
    }

    public static final class Result {
        public double f1, f2, v;      // v = Σ max(0, a_i(p) - 6)
        public double f2First;        // distancia de la 1ª etapa
        public int fullFirst;         // contenedores llenos al final de la 1ª etapa
        public State first;           // estado tras la 1ª etapa (solo si keepFirst)
    }

    /** q(0)=0 (informe) o, si Instance.daysSince != null, nivel estimado desde dias_sin_levantar y la tasa de llenado. */
    public State initial() {
        State s = new State(in.n);
        if (in.daysSince == null) return s;
        for (int i = 0; i < in.n; i++) {
            double daily = in.dq[0][i] + in.dq[1][i] + in.dq[2][i];
            if (daily <= 0) continue;
            double d = in.daysSince[i], tFill = Instance.CAP / daily;
            if (d >= tFill) { s.q[i] = Instance.CAP; s.age[i] = Math.min(Instance.MAX_AGE, 1 + (int) Math.floor((d - tFill) * Instance.H)); }
            else s.q[i] = daily * d;
        }
        return s;
    }

    /** Ejecuta un turno h con trucks[z] camiones en la zona z (recolecta con el estado de inicio, después genera). */
    public void step(State s, int h, int[] trucks) {
        double[] q = s.q;
        int[] age = s.age;
        for (int z = 0; z < in.nZones; z++) {
            int m = trucks[z];
            if (m == 0) continue;
            int[] ids = in.byZone[z];
            long[] keys = new long[ids.length];
            int c = 0;
            for (int j = 0; j < ids.length; j++) {
                int id = ids[j];
                if (q[id] > 0)   // prioridad: antigüedad, luego nivel (clave empaquetada -> sort primitivo)
                    keys[c++] = ((long) age[id] << 40) | ((long) Math.round(q[id]) << 20) | j;
            }
            Arrays.sort(keys, 0, c);
            double cap = m * Instance.Q, load = 0;
            for (int k = c - 1; k >= 0; k--) {
                int id = ids[(int) (keys[k] & 0xFFFFF)];
                if (load + q[id] > cap) break;
                load += q[id];
                q[id] = -1;                                       // marca: recolectado
            }
        }
        double[] d = in.dq[h];
        for (int i = 0; i < in.n; i++) {
            double v = q[i];
            int a = age[i];
            if (v < 0) { v = generateAfterCollect ? Math.min(Instance.CAP, d[i]) : 0; a = 0; }
            else v = Math.min(Instance.CAP, v + d[i]);
            q[i] = v;
            age[i] = v >= Instance.CAP - 1e-9 ? a + 1 : 0;
        }
    }

    /**
     * Decodifica y evalúa un plan de 3 etapas. genes[p*kMax + k] ∈ {0..Z}. Repara genes IN PLACE
     * (k >= flota del turno o zona no permitida -> 0), así el genotipo queda consistente (Lamarck).
     */
    public Result evaluate(State start, int h0, int[] genes, boolean keepFirst) {
        int Z = in.nZones, K = in.kMax;
        State s = start.copy();
        Result r = new Result();
        int[] trucks = new int[Z], nFull = new int[Z];
        for (int p = 0; p < Instance.H; p++) {
            int h = (h0 + p) % Instance.H;
            Arrays.fill(trucks, 0);
            double dist = 0;
            for (int k = 0; k < K; k++) {
                int idx = p * K + k, g = genes[idx];
                if (k >= in.fleet[h] || g < 0 || g > Z || (g > 0 && !in.allowed[h][g - 1])) { genes[idx] = 0; continue; }
                if (g > 0) { trucks[g - 1]++; dist += in.distSite[g - 1]; }
            }
            step(s, h, trucks);
            Arrays.fill(nFull, 0);
            int full = 0;
            for (int i = 0; i < in.n; i++) {
                if (s.q[i] >= Instance.CAP - 1e-9) { nFull[in.zone[i]]++; full++; }
                if (s.age[i] > Instance.MAX_AGE) r.v += s.age[i] - Instance.MAX_AGE;
            }
            double exc = 0;
            for (int z = 0; z < Z; z++) exc += Math.max(0, nFull[z] - in.tau * in.byZone[z].length);
            r.f1 += Instance.DELTA * (full + in.lambda * exc);
            r.f2 += dist;
            if (p == 0) { r.f2First = dist; r.fullFirst = full; if (keepFirst) r.first = s.copy(); }
        }
        return r;
    }
}
