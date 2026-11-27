package uy.fing.ae;

import uy.fing.ae.Simulator.State;

/** Baseline voraz determinista: por etapa, cada camión va a la zona permitida con mayor (llenos restantes, suma de antigüedades). */
public final class Greedy {
    private Greedy() {}

    public static int[] plan(Simulator sim, Instance in, State start, int h0) {
        int K = in.kMax, Z = in.nZones;
        int[] genes = new int[Instance.H * K];
        State s = start.copy();
        for (int p = 0; p < Instance.H; p++) {
            int h = (h0 + p) % Instance.H;
            int[] full = new int[Z];
            long[] ageSum = new long[Z];
            for (int i = 0; i < in.n; i++)
                if (s.q[i] >= Instance.CAP - 1e-9) { full[in.zone[i]]++; ageSum[in.zone[i]] += s.age[i]; }
            int[] trucks = new int[Z];
            for (int k = 0; k < in.fleet[h]; k++) {
                int best = -1, bestRem = 0;
                for (int z = 0; z < Z; z++) {
                    if (!in.allowed[h][z]) continue;
                    int rem = full[z] - Instance.PER_TRUCK * trucks[z];
                    if (rem <= 0) continue;
                    if (best < 0 || rem > bestRem || (rem == bestRem && ageSum[z] > ageSum[best])) { best = z; bestRem = rem; }
                }
                if (best < 0) break;                              // nada lleno que atender: camión sin usar
                trucks[best]++;
                genes[p * K + k] = best + 1;
            }
            sim.step(s, h, trucks);
        }
        return genes;
    }
}
