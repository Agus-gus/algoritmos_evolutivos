"""Métricas y estadística sobre out/fronts: hipervolumen, IGD+ (frente de referencia = no dominados de la unión),
Shapiro-Wilk, Mann-Whitney / Kruskal-Wallis + Dunn(Holm), Vargha-Delaney A12.  pip install numpy pandas scipy scikit-posthocs"""
import glob, os, itertools
import numpy as np, pandas as pd
from scipy import stats
import scikit_posthocs as sp

def nd(P):  # no dominados (minimización)
    P = np.unique(P, axis=0)
    keep = [i for i, p in enumerate(P) if not any((q <= p).all() and (q < p).any() for q in P)]
    return P[keep]

def hv2d(P, ref):
    P = nd(P); P = P[np.argsort(P[:, 0])]; hv, prev = 0.0, ref[1]
    for x, y in P:
        hv += (ref[0] - x) * (prev - y); prev = y
    return hv

def igd_plus(P, R):
    return np.mean([np.min(np.sqrt((np.maximum(P - z, 0) ** 2).sum(1))) for z in R])

def a12(x, y):  # P(x > y) + 0.5 P(=)
    x, y = np.asarray(x), np.asarray(y)
    return ((x[:, None] > y[None]).sum() + 0.5 * (x[:, None] == y[None]).sum()) / (len(x) * len(y))

fronts = {}
for f in glob.glob("out/fronts/*_*_*.csv"):
    alg, pen, run = os.path.basename(f)[:-4].split("_")
    d = pd.read_csv(f); d = d[d.v == 0]                       # solo factibles (R1)
    if len(d): fronts[(alg + "_" + pen, int(run))] = d[["f1", "f2"]].values
allp = np.vstack(list(fronts.values()))
lo, hi = allp.min(0), allp.max(0)
norm = lambda P: (P - lo) / (hi - lo + 1e-12)
ref = np.ones(2) * 1.1
R = nd(norm(allp))
rows = [dict(cfg=c, run=r, HV=hv2d(norm(P), ref), IGDp=igd_plus(norm(P), R), size=len(P)) for (c, r), P in fronts.items()]
df = pd.DataFrame(rows)
print(df.groupby("cfg")[["HV", "IGDp", "size"]].agg(["mean", "std"]).round(4))
if os.path.exists("out/fronts/greedy.csv"):
    g = pd.read_csv("out/fronts/greedy.csv")[["f1", "f2"]].values
    print("greedy HV:", round(hv2d(norm(g), ref), 4), "| IGD+:", round(igd_plus(norm(g), R), 4))

for metric in ("HV", "IGDp"):
    groups = {c: d[metric].values for c, d in df.groupby("cfg")}
    print(f"\n== {metric} ==")
    for c, v in groups.items(): print(c, "Shapiro p=%.3f" % stats.shapiro(v).pvalue)
    print("Kruskal-Wallis p=%.4g" % stats.kruskal(*groups.values()).pvalue)
    print(sp.posthoc_dunn(df, val_col=metric, group_col="cfg", p_adjust="holm").round(4))
    for c1, c2 in itertools.combinations(groups, 2):
        print(f"{c1} vs {c2}: MWU p=%.4g, A12=%.3f" % (stats.mannwhitneyu(groups[c1], groups[c2]).pvalue, a12(groups[c1], groups[c2])))
