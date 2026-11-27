package uy.fing.ae;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import uy.fing.ae.Simulator.*;

/**
 * Compara NSGA-II vs SPEA-2 y constrained-domination vs penalización suave sobre UN paso de planificación
 * (estado precalentado con el voraz). Escribe out/fronts/<alg>_<pen>_<run>.csv (f1,f2,v re-evaluados) y out/times.csv.
 * Uso: Experiment [corridas] [evals] [calentamiento] [dirDatos]
 */
public class Experiment {
    public static void main(String[] a) throws Exception {
        int runs = a.length > 0 ? Integer.parseInt(a[0]) : 30;
        int evals = a.length > 1 ? Integer.parseInt(a[1]) : 20000;
        int warm = a.length > 2 ? Integer.parseInt(a[2]) : 12;
        Instance in = a.length > 3 ? Instance.load(Path.of(a[3]))
                                   : Instance.synthetic(2000, 12, 42);
        Simulator sim = new Simulator(in);
        State st = sim.initial();
        for (int t = 0; t < warm; t++) st = sim.evaluate(st, t % 3, Greedy.plan(sim, in, st, t % 3), true).first;
        int h0 = warm % 3;
        Files.createDirectories(Path.of("out/fronts"));
        Result g = sim.evaluate(st, h0, Greedy.plan(sim, in, st, h0), false);
        try (PrintWriter w = new PrintWriter("out/fronts/greedy.csv")) { w.println("f1,f2,v"); w.printf(Locale.ROOT, "%f,%f,%f%n", g.f1, g.f2, g.v); }
        try (PrintWriter times = new PrintWriter("out/times.csv")) {
            times.println("alg,pen,run,seconds");
            for (String alg : new String[]{"nsga2", "spea2"})
                for (boolean soft : new boolean[]{false, true})
                    for (int run = 0; run < runs; run++) {
                        JMetalRandom.getInstance().setSeed(1000 + run);
                        WastePlanningProblem p = new WastePlanningProblem(sim, in, st, h0, soft, 1000);
                        p.seedInitialPopulation(100, null);
                        long t0 = System.nanoTime();
                        List<IntegerSolution> front = Solver.run(alg, p, in, 100, evals);
                        double sec = (System.nanoTime() - t0) / 1e9;
                        String pen = soft ? "soft" : "cdom";
                        try (PrintWriter w = new PrintWriter("out/fronts/" + alg + "_" + pen + "_" + run + ".csv")) {
                            w.println("f1,f2,v");
                            for (IntegerSolution s : front) {
                                int[] genes = new int[s.variables().size()];
                                for (int j = 0; j < genes.length; j++) genes[j] = s.variables().get(j);
                                Result r = sim.evaluate(st, h0, genes, false);
                                w.printf(Locale.ROOT, "%f,%f,%f%n", r.f1, r.f2, r.v);
                            }
                        }
                        times.printf(Locale.ROOT, "%s,%s,%d,%.2f%n", alg, pen, run, sec);
                        System.out.printf(Locale.ROOT, "%s/%s run %d: %d sol, %.1fs%n", alg, pen, run, front.size(), sec);
                    }
        }
    }
}
