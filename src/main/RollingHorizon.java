package uy.fing.ae;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import uy.fing.ae.Simulator.*;

/** Horizonte rodante: planifica 3 etapas con un MOEA, ejecuta la 1ª, avanza. Uso: RollingHorizon [nsga2|spea2] [pasos] [evals] [dirDatos] */
public class RollingHorizon {
    public static void main(String[] a) throws Exception {
        String alg = a.length > 0 ? a[0] : "nsga2";
        int steps = a.length > 1 ? Integer.parseInt(a[1]) : 21;
        int evals = a.length > 2 ? Integer.parseInt(a[2]) : 20000;
        Instance in = a.length > 3 ? Instance.load(Path.of(a[3]))
                                   : Instance.synthetic(2000, 12, 42);
        JMetalRandom.getInstance().setSeed(1);
        Simulator sim = new Simulator(in);
        State real = sim.initial();
        int[] prev = null;
        double hours = 0, dist = 0;
        Files.createDirectories(Path.of("out"));
        try (PrintWriter w = new PrintWriter("out/rolling_" + alg + ".csv")) {
            w.println("step,shift,fullAfter,distStage,r1viol");
            for (int t = 0; t < steps; t++) {
                int h0 = t % 3;
                WastePlanningProblem p = new WastePlanningProblem(sim, in, real, h0, false, 1000);
                p.seedInitialPopulation(100, prev);
                List<IntegerSolution> front = Solver.run(alg, p, in, 100, evals);
                int[] genes = Solver.pick(front, sim, in, real, h0);
                Result r = sim.evaluate(real, h0, genes, true);   // se ejecuta SOLO la 1ª etapa
                real = r.first;
                prev = genes;
                int viol = 0;
                for (int x : real.age) viol += Math.max(0, x - Instance.MAX_AGE);
                hours += Instance.DELTA * r.fullFirst; dist += r.f2First;
                w.printf(Locale.ROOT, "%d,%d,%d,%.0f,%d%n", t, h0, r.fullFirst, r.f2First, viol);
                System.out.printf(Locale.ROOT, "t=%d llenos=%d dist=%.0f violR1=%d frente=%d%n", t, r.fullFirst, r.f2First, viol, front.size());
            }
        }
        System.out.printf(Locale.ROOT, "TOTAL %s horas-llenos=%.0f distancia=%.0f%n", alg, hours, dist);
    }
}
