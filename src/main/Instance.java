package uy.fing.ae;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Datos del problema. Zonas 0-based internamente (en el genotipo: gen 0 = camión sin usar, gen g = zona g-1). */
public class Instance {
    public static final double CAP = 500.0;                 // kg por contenedor
    public static final double Q = 14000.0;                 // kg por camión
    public static final int PER_TRUCK = (int) (Q / CAP);    // 28 contenedores llenos por camión
    public static final double DELTA = 8.0;                 // horas por turno
    public static final int MAX_AGE = 6;                    // 48 h = 6 turnos (R1)
    public static final int H = 3;
    public static final double[] ALPHA = {0.3, 0.5, 0.2};   // mañana, tarde, noche

    public int n, nZones, kMax;
    public int[] zone;              // z(i)
    public double[][] dq;           // [h][i] kg generados por turno
    public int[] fleet = {33, 30, 34};
    public boolean[][] allowed;     // [h][z]  R2: derivado del índice de tránsito
    public double[] distSite;       // [z] distancia centroide -> Felipe Cardoso (UTM, m)
    public int[][] byZone;          // contenedores de cada zona
    public double tau = 0.10;       // tolerancia por zona
    public double lambda = 1.0;     // peso de la penalización por tolerancia

    public void finish() {
        kMax = Arrays.stream(fleet).max().getAsInt();
        int[] cnt = new int[nZones];
        for (int z : zone) cnt[z]++;
        byZone = new int[nZones][];
        for (int z = 0; z < nZones; z++) byZone[z] = new int[cnt[z]];
        Arrays.fill(cnt, 0);
        for (int i = 0; i < n; i++) byZone[zone[i]][cnt[zone[i]]++] = i;
        active = java.util.stream.IntStream.range(0, nZones).filter(z -> byZone[z].length > 0).toArray();
        if (zoneId == null) { zoneId = new int[nZones]; for (int z = 0; z < nZones; z++) zoneId[z] = z; }
    }

    // ---------- carga desde datos/ (generado por preparar_datos.py; EPSG:32721 en metros) ----------
    // zonas.csv:       id_zona,cx,cy,dist_sitio,n_contenedores,municipio
    // contenedores.csv: x,y,id_zona,dias_sin_levantar
    // manzanas.csv:    x,y,pob
    // transito.csv:    id_zona,turno(1..3),nivel(Alta|Media|Baja)
    public double[] daysSince;      // dias_sin_levantar por contenedor (null si no se usa como condición inicial)
    public int[] zoneId;            // id_zona original de cada índice de zona
    public int[] active;            // índices de zonas con al menos un contenedor

    public static final double DEFAULT_R = 300, DEFAULT_BETA = 1.5;   // PLACEHOLDERS: radio peatonal y decaimiento IDW

    public static Instance load(Path dir) throws IOException {
        return load(dir, DEFAULT_R, DEFAULT_BETA, Set.of("Alta"), false);
    }

    /** blocked: niveles de tránsito en los que NO se puede recolectar (R2). initFromLevantes: q(0) a partir de dias_sin_levantar. */
    public static Instance load(Path dir, double R, double beta, Set<String> blocked, boolean initFromLevantes) throws IOException {
        Instance in = new Instance();
        Csv zs = new Csv(dir.resolve("zonas.csv"));
        in.nZones = zs.size();
        in.zoneId = new int[in.nZones];
        in.distSite = new double[in.nZones];
        Map<Integer, Integer> zidx = new HashMap<>();
        for (int z = 0; z < in.nZones; z++) {
            in.zoneId[z] = (int) Double.parseDouble(zs.get(z, "id_zona"));
            zidx.put(in.zoneId[z], z);
            in.distSite[z] = Double.parseDouble(zs.get(z, "dist_sitio"));
        }
        Csv cs = new Csv(dir.resolve("contenedores.csv"));
        in.n = cs.size();
        in.zone = new int[in.n];
        in.daysSince = new double[in.n];
        double[] cx = new double[in.n], cy = new double[in.n];
        for (int i = 0; i < in.n; i++) {
            cx[i] = Double.parseDouble(cs.get(i, "x"));
            cy[i] = Double.parseDouble(cs.get(i, "y"));
            in.zone[i] = zidx.get((int) Double.parseDouble(cs.get(i, "id_zona")));
            String d = cs.get(i, "dias_sin_levantar");
            in.daysSince[i] = d.isBlank() ? 0 : Double.parseDouble(d);
        }
        if (!initFromLevantes) in.daysSince = null;
        Csv ms = new Csv(dir.resolve("manzanas.csv"));
        double[] mx = new double[ms.size()], my = new double[ms.size()], pop = new double[ms.size()];
        for (int m = 0; m < ms.size(); m++) {
            mx[m] = Double.parseDouble(ms.get(m, "x"));
            my[m] = Double.parseDouble(ms.get(m, "y"));
            pop[m] = Double.parseDouble(ms.get(m, "pob"));
        }
        double[] omega = new double[in.n];
        Arrays.fill(omega, 1.0);                                  // ω_b(i) = 1 por defecto
        in.dq = Generation.dq(cx, cy, mx, my, pop, R, beta, 1.1, ALPHA, omega);
        in.allowed = new boolean[H][in.nZones];
        for (boolean[] row : in.allowed) Arrays.fill(row, true);
        Csv tr = new Csv(dir.resolve("transito.csv"));
        for (int r = 0; r < tr.size(); r++)
            in.allowed[Integer.parseInt(tr.get(r, "turno")) - 1][zidx.get((int) Double.parseDouble(tr.get(r, "id_zona")))] =
                !blocked.contains(tr.get(r, "nivel").trim());
        in.finish();
        return in;
    }

    /** CSV con encabezado, separador coma, UTF-8 (lo escribe preparar_datos.py, sin comillas ni comas internas). */
    private static final class Csv {
        final List<String[]> rows = new ArrayList<>();
        final Map<String, Integer> col = new HashMap<>();
        Csv(Path p) throws IOException {
            List<String> lines = Files.readAllLines(p);
            String[] h = lines.get(0).split(",", -1);
            for (int j = 0; j < h.length; j++) col.put(h[j].trim(), j);
            for (int i = 1; i < lines.size(); i++) if (!lines.get(i).isBlank()) rows.add(lines.get(i).split(",", -1));
        }
        int size() { return rows.size(); }
        String get(int r, String name) { return rows.get(r)[col.get(name)].trim(); }
    }

    // ---------- instancia sintética (misma relación flota/generación que Montevideo, escalada) ----------
    public static Instance synthetic(int n, int nZones, long seed) {
        Random r = new Random(seed);
        Instance in = new Instance();
        in.n = n; in.nZones = nZones;
        in.zone = new int[n];
        in.dq = new double[H][n];
        for (int i = 0; i < n; i++) {
            in.zone[i] = r.nextInt(nZones);
            double daily = 60 + 160 * r.nextDouble();             // kg/día/contenedor (media ~140)
            for (int h = 0; h < H; h++) in.dq[h][i] = ALPHA[h] * daily;
        }
        in.fleet = new int[]{Math.max(1, (int) Math.round(n * 33 / 10461.0)),
                             Math.max(1, (int) Math.round(n * 30 / 10461.0)),
                             Math.max(1, (int) Math.round(n * 34 / 10461.0))};
        in.allowed = new boolean[H][nZones];
        for (int h = 0; h < H; h++)
            for (int z = 0; z < nZones; z++)
                in.allowed[h][z] = !(h == 0 && z < Math.max(1, nZones / 5)); // zonas "céntricas" vedadas a la mañana
        in.distSite = new double[nZones];
        for (int z = 0; z < nZones; z++) in.distSite[z] = 3000 + 20000 * r.nextDouble();
        in.finish();
        return in;
    }
}
