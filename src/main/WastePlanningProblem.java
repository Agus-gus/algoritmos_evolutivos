package uy.fing.ae;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.uma.jmetal.problem.integerproblem.IntegerProblem;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

import uy.fing.ae.Simulator.*;
import uy.fing.ae.Simulator.Result;
import uy.fing.ae.Simulator.State;

/**
 * Plan de 3 etapas: gen[p*kMax + k] ∈ {0..Z} = zona del camión k en la etapa p (0 = sin usar).
 * f1 = horas llenos + penalización por tolerancia, f2 = distancia al sitio. R1 por constrained-domination
 * (softPenalty=false) o penalización suave f'_j = f_j + λv·v (softPenalty=true).
 */
public class WastePlanningProblem implements IntegerProblem {
    private final Simulator sim;
    private final Instance in;
    private final State start;
    private final int h0, nv;
    private final boolean soft;
    private final double lambdaV;
    private final List<Bounds<Integer>> bounds;
    private final ArrayDeque<int[]> seeds = new ArrayDeque<>();

    public WastePlanningProblem(Simulator sim, Instance in, State start, int h0, boolean softPenalty, double lambdaV) {
        this.sim = sim;
        this.in = in;
        this.start = start;
        this.h0 = h0;
        this.soft = softPenalty;
        this.lambdaV = lambdaV;
        this.nv = Instance.H * in.kMax;

        List<Bounds<Integer>> bList = new ArrayList<>(nv);
        for (int j = 0; j < nv; j++) {
            bList.add(Bounds.create(0, in.nZones));
        }
        this.bounds = Collections.unmodifiableList(bList);
    }

    @Override
    public int numberOfVariables() { return nv; }

    @Override
    public int numberOfObjectives() { return 2; }

    @Override
    public int numberOfConstraints() { return soft ? 0 : 1; }

    @Override
    public String name() { return "WastePlanning"; }

    @Override
    public List<Bounds<Integer>> variableBounds() {
        return bounds;
    }

    public int numberOfGenes() { return nv; }

    /** Población inicial mixta: voraz, plan previo desplazado, voraz perturbado; el resto sale aleatorio de createSolution(). */
    public void seedInitialPopulation(int popSize, int[] prevPlan) {
        JMetalRandom r = JMetalRandom.getInstance();
        int[] g = Greedy.plan(sim, in, start, h0);
        seeds.add(g.clone());
        if (prevPlan != null) seeds.add(shift(prevPlan, in.kMax));
        for (int s = 0; s < popSize / 4; s++) {
            int[] c = g.clone();
            for (int j = 0; j < nv; j++) {
                if (r.nextDouble() < 0.1) {
                    c[j] = r.nextDouble() < 0.3 ? 0 : 1 + in.active[r.nextInt(0, in.active.length - 1)];
                }
            }
            seeds.add(c);
        }
    }

    /** Plan de la ventana anterior corrido una etapa (la última se repite). */
    public static int[] shift(int[] prev, int k) {
        int[] g = new int[prev.length];
        for (int p = 0; p < Instance.H; p++) {
            System.arraycopy(prev, Math.min(p + 1, Instance.H - 1) * k, g, p * k, k);
        }
        return g;
    }

    @Override
    public IntegerSolution createSolution() {
        IntegerSolution s = new DefaultIntegerSolution(variableBounds(), numberOfObjectives(), numberOfConstraints());
        int[] seed = seeds.poll();
        JMetalRandom r = JMetalRandom.getInstance();
        for (int j = 0; j < nv; j++) {
            int val = seed != null ? seed[j] : (r.nextDouble() < 0.3 ? 0 : 1 + in.active[r.nextInt(0, in.active.length - 1)]);
            s.variables().set(j, val);
        }
        return s;
    }

    @Override
    public IntegerSolution evaluate(IntegerSolution s) {
        int[] genes = new int[nv];
        for (int j = 0; j < nv; j++) genes[j] = s.variables().get(j);
        Result r = sim.evaluate(start, h0, genes, false);
        for (int j = 0; j < nv; j++) s.variables().set(j, genes[j]); // genotipo reparado
        double f1 = r.f1, f2 = r.f2;
        if (soft) {
            f1 += lambdaV * r.v;
            f2 += lambdaV * r.v;
        } else {
            s.constraints()[0] = -r.v; // < 0 = violación (jMetal)
        }
        s.objectives()[0] = f1;
        s.objectives()[1] = f2;
        return s;
    }
}