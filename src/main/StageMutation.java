package uy.fing.ae;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

/** Reasignación de zona por gen + intercambio de asignaciones entre etapas. */
public class StageMutation implements MutationOperator<IntegerSolution> {
    private final double pm, pSwap;
    private final int k;
    private final int[] active;

    public StageMutation(double pm, double pSwap, int kMax, int[] activeZones) { this.pm = pm; this.pSwap = pSwap; this.k = kMax; this.active = activeZones; }

    @Override 
    public double mutationProbability() { 
        return pm; 
    }

    @Override
    public IntegerSolution execute(IntegerSolution s) {
        JMetalRandom r = JMetalRandom.getInstance();
        for (int j = 0; j < s.variables().size(); j++)
            if (r.nextDouble() < pm) s.variables().set(j, r.nextDouble() < 0.3 ? 0 : 1 + active[r.nextInt(0, active.length - 1)]);
        if (r.nextDouble() < pSwap) {
            int p1 = r.nextInt(0, Instance.H - 1), p2 = r.nextInt(0, Instance.H - 1), kk = r.nextInt(0, k - 1);
            if (p1 != p2) {
                Integer t = s.variables().get(p1 * k + kk);
                s.variables().set(p1 * k + kk, s.variables().get(p2 * k + kk));
                s.variables().set(p2 * k + kk, t);
            }
        }
        return s;
    }
}
