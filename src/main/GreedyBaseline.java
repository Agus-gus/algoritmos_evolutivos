package uy.fing.ae;

import java.nio.file.*;
import java.util.Locale;
import uy.fing.ae.Simulator.*;

/** Horizonte rodante con el baseline voraz (sin jMetal). Uso: GreedyBaseline [pasos] [dirDatos] */
public class GreedyBaseline {
    public static void main(String[] a) throws Exception {
        int steps = a.length > 0 ? Integer.parseInt(a[0]) : 21;
        Instance in = a.length > 1 ? Instance.load(Path.of(a[1])) : Instance.synthetic(2000, 12, 42);
        Simulator sim = new Simulator(in);
        State st = sim.initial();
        double hours = 0, dist = 0;
        for (int t = 0; t < steps; t++) {
            int[] g = Greedy.plan(sim, in, st, t % 3);
            Result r = sim.evaluate(st, t % 3, g, true);
            st = r.first;
            int viol = 0;
            for (int x : st.age) viol += Math.max(0, x - Instance.MAX_AGE);
            hours += Instance.DELTA * r.fullFirst; dist += r.f2First;
            System.out.printf(Locale.ROOT, "t=%d turno=%d llenos=%d dist=%.0f violR1=%d%n", t, t % 3, r.fullFirst, r.f2First, viol);
        }
        System.out.printf(Locale.ROOT, "TOTAL horas-llenos=%.0f distancia=%.0f%n", hours, dist);
    }
}
