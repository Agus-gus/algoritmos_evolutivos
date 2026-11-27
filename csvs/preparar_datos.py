"""Prepara datos/ para el código Java a partir de los CSV de AE (zonas, contenedores_con_zona, icen_poblacion_23).
Uso: python preparar_datos.py <carpeta con los CSV> [carpeta_salida=datos]      (pip install pandas numpy pyproj)"""
import os, sys
import numpy as np, pandas as pd
from pyproj import Transformer

BASE = sys.argv[1] if len(sys.argv) > 1 else "."
OUT = sys.argv[2] if len(sys.argv) > 2 else "datos"

# --- PENDIENTE: coordenadas (lat, lon) de la Usina / Sitio de Disposición Final Felipe Cardoso ---
SITE_LATLON = (-34.848613, -56.094328)            # ej.: (-34.xxxx, -56.xxxx)  <- copiarlas de Google Maps / IM
# --- PROVISORIO: regla de tránsito (no hay archivo). nivel Alta => el Java bloquea la recolección (R2) ---
ALTA_POR_TURNO = {1: {"B", "CH"}, 2: set(), 3: set()}        # turno -> municipios con tránsito Alta
MEDIA_POR_TURNO = {1: set(), 2: {"B", "CH"}, 3: set()}       # (solo informativo; por defecto Media no bloquea)

if SITE_LATLON is None:
    sys.exit("Falta SITE_LATLON (lat, lon de la Usina Felipe Cardoso) en preparar_datos.py")

to_utm = Transformer.from_crs(4326, 32721, always_xy=True)
os.makedirs(OUT, exist_ok=True)

zon = pd.read_csv(os.path.join(BASE, "zonas.csv"))
cont = pd.read_csv(os.path.join(BASE, "contenedores_con_zona.csv"))
pob = pd.read_csv(os.path.join(BASE, "icen_poblacion_23.csv"), decimal=",", encoding="utf-8-sig")
pob.columns = [c.split(",")[0] for c in pob.columns]           # 'X_CEN,N,24,15' -> 'X_CEN'

# contenedores reales (descarta filas de metadatos: sin zona o sin coordenadas)
lon_c = "lon" if "lon" in cont.columns else "longitud"
lat_c = "lat" if "lat" in cont.columns else "latitud"
cont = cont[cont["id_zona"].notna() & cont[lon_c].notna() & cont[lat_c].notna()].copy()
cont["x"], cont["y"] = to_utm.transform(cont[lon_c].values, cont[lat_c].values)
cont["id_zona"] = cont["id_zona"].astype(int)
assert set(cont.id_zona) <= set(zon.id_zona), "contenedores con id_zona que no está en zonas.csv"
cont["dias_sin_levantar"] = pd.to_numeric(cont.get("dias_sin_levantar"), errors="coerce").fillna(0)
cont[["x", "y", "id_zona", "dias_sin_levantar"]].round(2).to_csv(os.path.join(OUT, "contenedores.csv"), index=False)

# población: coordenadas en grados WGS84 -> UTM 21S
if "NOMDEPTO" in pob.columns and pob["NOMDEPTO"].nunique() > 1:
    pob = pob[pob["NOMDEPTO"].astype(str).str.upper().str.contains("MONTEVIDEO")]
pob = pob[pob["POB_TOT_23"] > 0].copy()
pob["x"], pob["y"] = to_utm.transform(pob["X_CEN"].values, pob["Y_CEN"].values)
pob[["x", "y"]].assign(pob=pob["POB_TOT_23"]).round(2).to_csv(os.path.join(OUT, "manzanas.csv"), index=False)

# zonas + distancia al sitio
sx, sy = to_utm.transform(SITE_LATLON[1], SITE_LATLON[0])
zon["dist_sitio"] = np.hypot(zon.centroide_x - sx, zon.centroide_y - sy).round(1)
zon["n_contenedores"] = zon.id_zona.map(cont.id_zona.value_counts()).fillna(0).astype(int)
zon.rename(columns={"centroide_x": "cx", "centroide_y": "cy"})[
    ["id_zona", "cx", "cy", "dist_sitio", "n_contenedores", "municipio"]].to_csv(os.path.join(OUT, "zonas.csv"), index=False)

# tránsito provisorio por municipio y turno
rows = []
for _, z in zon.iterrows():
    for h in (1, 2, 3):
        nivel = "Alta" if z.municipio in ALTA_POR_TURNO[h] else "Media" if z.municipio in MEDIA_POR_TURNO[h] else "Baja"
        rows.append((z.id_zona, h, nivel))
pd.DataFrame(rows, columns=["id_zona", "turno", "nivel"]).to_csv(os.path.join(OUT, "transito.csv"), index=False)

# chequeos de escala
gen_dia = 1.1 * pob["POB_TOT_23"].sum()
cap_dia = (33 + 30 + 34) * 14000
print(f"contenedores={len(cont)} zonas={len(zon)} (con contenedores: {(zon.n_contenedores>0).sum()}) poblacion={pob['POB_TOT_23'].sum():,.0f}")
print(f"generacion/dia={gen_dia:,.0f} kg | capacidad de flota/dia (1 carga/turno)={cap_dia:,.0f} kg | ratio={gen_dia/cap_dia:.3f}")
print(f"dias_sin_levantar: media={cont.dias_sin_levantar.mean():.2f} max={cont.dias_sin_levantar.max():.0f}")
