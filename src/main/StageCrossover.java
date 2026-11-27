package uy.fing.ae;

import java.util.List;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

/** Cruzamiento por etapas (intercambia etapas completas) o uniforme por gen. */
public class StageCrossover implements CrossoverOperator<IntegerSolution> {
    private final double prob;
    private final int k;
    private final boolean byStage;

    public StageCrossover(double prob, int kMax, boolean byStage) {
        this.prob = prob;
        this.k = kMax;
        this.byStage = byStage;
    }

    @Override
    public double crossoverProbability() {
        return prob;
    }

    @Override
    public int numberOfRequiredParents() { return 2; }

    @Override
    public int numberOfGeneratedChildren() { return 2; }

    @Override
    public List<IntegerSolution> execute(List<IntegerSolution> parents) {
        IntegerSolution a = (IntegerSolution) parents.get(0).copy();
        IntegerSolution b = (IntegerSolution) parents.get(1).copy();
        JMetalRandom r = JMetalRandom.getInstance();
        if (r.nextDouble() <= prob) {
            boolean[] swapStage = new boolean[Instance.H];
            for (int p = 0; p < Instance.H; p++) swapStage[p] = r.nextDouble() < 0.5;
            for (int j = 0; j < a.variables().size(); j++) {
                boolean swap = byStage ? swapStage[j / k] : r.nextDouble() < 0.5;
                if (swap) {
                    Integer t = a.variables().get(j);
                    a.variables().set(j, b.variables().get(j));
                    b.variables().set(j, t);
                }
            }
        }
        return List.of(a, b);
    }
}