# ============================================================
# ZONAS — carga, fusión de slivers, asignación de contenedores
# ============================================================
import re
import numpy as np
import pandas as pd
import geopandas as gpd
from shapely import wkt
from shapely.ops import unary_union

# ---------------- Config ----------------
UMBRAL_AREA_SLIVER_M2 = 50_000          # 5 ha
ESTRATEGIA            = "nearest"       # "nearest" | "smallest_neighbor" | "longest_border"
MIN_CONTENEDORES_ZONA = 5               # segunda pasada (opcional)


# ---------------- Utilidades ----------------
def fix_coord(s, is_lon=True):
    """Convierte '5654321' -> 56.54321 respetando el signo y el bounding box."""
    if pd.isna(s):
        return np.nan
    s = str(s).strip().replace(",", ".")
    m = re.match(r"^(-?)(\d+)(\.\d+)?$", s)
    if not m:
        return np.nan
    sign, intpart, dec = m.groups()
    if dec is None and len(intpart) > 2:
        intpart = intpart[:2] + "." + intpart[2:]
    v = float(sign + intpart + (dec or ""))
    ok_lon = -58 < v < -55
    ok_lat = -35 < v < -33
    return v if ((is_lon and ok_lon) or (not is_lon and ok_lat)) else np.nan


# ---------------- 1. Contenedores ----------------
def cargar_contenedores(path="levantes-contenedores.csv"):
    df = pd.read_csv(path, dtype=str, encoding="latin-1")
    df = df[df["Campo"].isna()].copy()
    df["lon"] = df["longitud"].apply(fix_coord)
    df["lat"] = df["latitud"].apply(lambda s: fix_coord(s, is_lon=False)) 
    df = df.dropna(subset=["lon", "lat"]).copy()
    df["dias_sin_levantar"] = pd.to_numeric(df["dias_sin_levantar"], errors="coerce")
    df = df.dropna(subset=["dias_sin_levantar"]).copy()
    df["id_tacho"] = df["cod_recorrido"] + "_" + df["posicion"]
    print(f"[contenedores] {len(df)} filas válidas, "
          f"{df['id_tacho'].nunique()} tachos únicos")
    return df


# ---------------- 2. Circuitos ----------------
def cargar_circuitos(path="Circuitos_recoleccion.csv"):
    df = pd.read_csv(path, sep=";", encoding="utf-8")
    df.columns = [c.strip() for c in df.columns]
    df["COD_CIRCUITO"] = df["COD_CIRCUITO"].str.strip()
    df["MUNICIPIO"]    = df["MUNICIPIO"].str.strip()
    df["geometry"]     = df["VERTICES"].apply(wkt.loads)
    gdf = gpd.GeoDataFrame(df, geometry="geometry", crs="EPSG:32721")
    gdf["area_m2"]     = gdf.geometry.area
    gdf["centroide_x"] = gdf.geometry.centroid.x
    gdf["centroide_y"] = gdf.geometry.centroid.y
    return gdf


def diagnosticar_circuitos(gdf, umbral_area_m2=UMBRAL_AREA_SLIVER_M2):
    print(f"Total circuitos: {len(gdf)}")
    dup = gdf[gdf.duplicated("COD_CIRCUITO", keep=False)]
    if not dup.empty:
        print(f"  COD_CIRCUITO duplicado en {len(dup)} filas "
              f"({dup['COD_CIRCUITO'].nunique()} códigos). Se usa GID como clave.")
    print("\nDistribución de áreas (m²):")
    print(gdf["area_m2"].describe(percentiles=[.05, .10, .25, .50, .75, .90])
          .round(0).to_string())
    slivers = gdf[gdf["area_m2"] < umbral_area_m2].sort_values("area_m2")
    print(f"\nSlivers (< {umbral_area_m2:,.0f} m²): {len(slivers)}")
    if not slivers.empty:
        print(slivers[["GID", "COD_CIRCUITO", "MUNICIPIO", "area_m2"]]
              .to_string(index=False))


# ---------------- 3. Fusión ----------------
def _vecinos(gdf, idx, tol=1.0):
    """Índices de polígonos que tocan (o casi tocan) al polígono idx."""
    g = gdf.loc[idx, "geometry"]
    otros = gdf.drop(idx)
    mask = otros.geometry.touches(g)
    if not mask.any():
        mask = otros.geometry.intersects(g.buffer(tol))
    return otros[mask].index if mask.any() else otros.index


def fusionar_slivers(gdf, umbral_area_m2=UMBRAL_AREA_SLIVER_M2,
                     strategy=ESTRATEGIA, max_iter=None, verbose=True):
    """
    Fusiona polígonos con área < umbral_area_m2 con un vecino.
      nearest:           vecino con centroide más cercano
      smallest_neighbor: vecino de menor área
      longest_border:    vecino con borde compartido más largo
    """
    if max_iter is None:
        max_iter = len(gdf) * 2

    gdf = gdf.copy().reset_index(drop=True)
    gdf["absorbe"] = gdf["COD_CIRCUITO"].fillna("")

    for _ in range(max_iter):
        slivers = gdf[gdf["area_m2"] < umbral_area_m2]
        if slivers.empty:
            break

        idx = slivers["area_m2"].idxmin()
        sliver = gdf.loc[idx]
        sliver_geom = sliver["geometry"]

        cand = _vecinos(gdf, idx)
        if len(cand) == 0:
            if verbose:
                print(f"  ⚠️  {sliver['COD_CIRCUITO']} sin vecinos, se deja como está")
            break

        if strategy == "nearest":
            target = (gdf.loc[cand, "geometry"].centroid
                      .distance(sliver_geom.centroid).idxmin())
        elif strategy == "smallest_neighbor":
            target = gdf.loc[cand, "area_m2"].idxmin()
        elif strategy == "longest_border":
            border = (gdf.loc[cand, "geometry"].boundary
                      .intersection(sliver_geom.boundary).length)
            target = border.idxmax()
        else:
            raise ValueError(f"strategy desconocida: {strategy}")

        # guardar el código del target ANTES de dropear
        target_code = gdf.loc[target, "COD_CIRCUITO"]

        new_geom = unary_union([gdf.loc[target, "geometry"], sliver_geom])
        gdf.at[target, "geometry"] = new_geom
        gdf.at[target, "area_m2"]  = new_geom.area
        gdf.at[target, "absorbe"]  = gdf.at[target, "absorbe"] + "|" + sliver["COD_CIRCUITO"]

        gdf = gdf.drop(idx).reset_index(drop=True)

        if verbose:
            print(f"  fusionado {sliver['COD_CIRCUITO']} "
                  f"({sliver['area_m2']:.0f} m²) → {target_code}")

    gdf["centroide_x"] = gdf.geometry.centroid.x
    gdf["centroide_y"] = gdf.geometry.centroid.y
    return gdf.reset_index(drop=True)


# ---------------- 4. Asignación de contenedores a zonas ----------------
def asignar_contenedores_a_zonas(cont_df, zonas_gdf,
                                 umbral_auditoria_m=500, verbose=True):
    """
    Asigna contenedores a zonas por point-in-polygon.
    - Contenedores fuera: se asignan al polígono más cercano.
    - Si verbose, reporta cuántos quedaron fuera y los que están a >umbral_m.
    """
    gdf_pts = gpd.GeoDataFrame(
        cont_df,
        geometry=gpd.points_from_xy(cont_df["lon"], cont_df["lat"]),
        crs="EPSG:4326",
    ).to_crs("EPSG:32721")

    cols_zona = ["id_zona", "codigo", "municipio", "geometry"]
    joined = gpd.sjoin(gdf_pts, zonas_gdf[cols_zona],
                       how="left", predicate="within")

    faltan = joined["id_zona"].isna()
    if faltan.any():
        # --- auditoría ANTES de reasignar ---
        dist = joined.loc[faltan].geometry.apply(
            lambda p: zonas_gdf.geometry.distance(p).min()
        )
        fuera_lejos = joined.loc[faltan][dist > umbral_auditoria_m]

        if verbose:
            print(f"  {faltan.sum()} contenedores fuera de polígono, "
                  f"{len(fuera_lejos)} a >{umbral_auditoria_m} m del más cercano")
            if not fuera_lejos.empty:
                cols = ["id_tacho", "lon", "lat"]
                print(fuera_lejos[cols]
                      .assign(dist_m=dist[fuera_lejos.index].round(0))
                      .sort_values("dist_m", ascending=False)
                      .to_string(index=False))

        # --- reasignación al más cercano ---
        for i in joined[faltan].index:
            pt = joined.loc[i, "geometry"]
            nearest = zonas_gdf.geometry.distance(pt).idxmin()
            joined.at[i, "id_zona"] = zonas_gdf.loc[nearest, "id_zona"]

    return joined.drop(columns=["geometry", "index_right"], errors="ignore")

if __name__ == "__main__":
    # 1) contenedores
    pdf_cont = cargar_contenedores("levantes-contenedores.csv")

    # 2) circuitos
    circuitos = cargar_circuitos("Circuitos_recoleccion.csv")
    diagnosticar_circuitos(circuitos, UMBRAL_AREA_SLIVER_M2)

    # 3) fusión de slivers
    zonas = fusionar_slivers(circuitos, umbral_area_m2=UMBRAL_AREA_SLIVER_M2,
                             strategy=ESTRATEGIA, verbose=True)

    # 4) normalizar nombres
    zonas = zonas.rename(columns={
        "GID": "id_zona", "COD_CIRCUITO": "codigo", "MUNICIPIO": "municipio",
    })
    zonas["id_zona"] = zonas["id_zona"].astype(int)

    print(f"\nZonas finales: {len(zonas)} "
          f"(original: {len(circuitos)}, fusionadas: {len(circuitos) - len(zonas)})")

    # 5) asignar contenedores (la auditoría va adentro)
    cont_zonas = asignar_contenedores_a_zonas(pdf_cont, zonas, verbose=True)

    # 6) diagnóstico post-asignación
    conteo = cont_zonas.groupby("id_zona").size().rename("n_contenedores")
    resumen = zonas.set_index("id_zona").join(conteo).fillna({"n_contenedores": 0})
    resumen["n_contenedores"] = resumen["n_contenedores"].astype(int)

    print("\nContenedores por zona:")
    print(resumen["n_contenedores"].describe().round(1).to_string())

    print("\nZonas con < 5 contenedores:")
    print(resumen[resumen["n_contenedores"] < 5]
          [["codigo", "municipio", "area_m2", "n_contenedores"]]
          .sort_values("n_contenedores").to_string())

    # 7) guardar
    resumen_out = resumen.reset_index().drop(columns=["VERTICES"], errors="ignore")
    resumen_out.to_file("zonas.geojson", driver="GeoJSON")
    resumen_out.drop(columns=["geometry"]).to_csv("zonas.csv", index=False)

    cont_zonas.to_csv("contenedores_con_zona.csv", index=False)
    print("\nGuardado: zonas.geojson, zonas.csv, contenedores_con_zona.csv")