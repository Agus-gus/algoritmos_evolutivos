package uy.fing.ae;

import java.util.List;
import org.uma.jmetal.algorithm.Algorithm;
import org.uma.jmetal.algorithm.multiobjective.nsgaii.NSGAIIBuilder;
import org.uma.jmetal.algorithm.multiobjective.spea2.SPEA2Builder;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

public final class Solver {
    private Solver() {}

    /** alg = "nsga2" | "spea2". Devuelve el frente aproximado. */
    public static List<IntegerSolution> run(String alg, WastePlanningProblem p, Instance in, int popSize, int maxEvals) {
        var cx = new StageCrossover(0.9, in.kMax, true);
        var mut = new StageMutation(1.0 / p.numberOfGenes(), 0.2, in.kMax, in.active);
        Algorithm<List<IntegerSolution>> a;
        if (alg.equals("nsga2"))
            a = new NSGAIIBuilder<>(p, cx, mut, popSize).setMaxEvaluations(maxEvals).build();
        else   // SPEA2 itera por generaciones; si no respeta constraints usar softPenalty=true
            a = new SPEA2Builder<>(p, cx, mut).setPopulationSize(popSize).setMaxIterations(maxEvals / popSize).build();
        a.run();
        return a.result();
    }

    /** Elige del frente la solución factible (v mínimo) más cercana al punto ideal con objetivos normalizados. */
    public static int[] pick(List<IntegerSolution> front, Simulator sim, Instance in, Simulator.State st, int h0) {
        int m = front.size();
        int[][] genes = new int[m][];
        double[][] f = new double[m][];
        double minV = Double.MAX_VALUE;
        for (int s = 0; s < m; s++) {
            genes[s] = new int[front.get(s).variables().size()];
            for (int j = 0; j < genes[s].length; j++) genes[s][j] = front.get(s).variables().get(j);
            Simulator.Result r = sim.evaluate(st, h0, genes[s], false);
            f[s] = new double[]{r.f1, r.f2, r.v};
            minV = Math.min(minV, r.v);
        }
        double lo1 = Double.MAX_VALUE, hi1 = -1, lo2 = Double.MAX_VALUE, hi2 = -1;
        for (double[] x : f) if (x[2] == minV) { lo1 = Math.min(lo1, x[0]); hi1 = Math.max(hi1, x[0]); lo2 = Math.min(lo2, x[1]); hi2 = Math.max(hi2, x[1]); }
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int s = 0; s < m; s++) {
            if (f[s][2] != minV) continue;
            double d1 = (f[s][0] - lo1) / Math.max(1e-9, hi1 - lo1), d2 = (f[s][1] - lo2) / Math.max(1e-9, hi2 - lo2);
            double d = d1 * d1 + d2 * d2;
            if (d < bd) { bd = d; best = s; }
        }
        return genes[best];
    }
}
