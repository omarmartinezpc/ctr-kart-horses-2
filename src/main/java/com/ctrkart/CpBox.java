package com.ctrkart;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Un checkpoint: una caja de bloques (esquinas incluidas, coordenadas enteras) con un numero.
 * number 0 = META, 1..100 = recorrido, FREE (-1) = "zona sin restricciones" (ahi no se aplican las reglas).
 */
public record CpBox(int number, int x1, int y1, int z1, int x2, int y2, int z2) {
    public static final int FREE = -1;
    public static final int MAX_NUMBER = 100;

    public static CpBox of(int number, BlockPos a, BlockPos b) {
        return new CpBox(number,
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public boolean isFree() {
        return number == FREE;
    }

    public int min(int axis) {
        return axis == 0 ? x1 : axis == 1 ? y1 : z1;
    }

    public int max(int axis) {
        return axis == 0 ? x2 : axis == 1 ? y2 : z2;
    }

    public int size(int axis) {
        return max(axis) - min(axis) + 1;
    }

    public boolean contains(Vec3 p) {
        return p.x >= x1 && p.x < x2 + 1 && p.y >= y1 && p.y < y2 + 1 && p.z >= z1 && p.z < z2 + 1;
    }

    public Vec3 center() {
        return new Vec3((x1 + x2 + 1) / 2.0, (y1 + y2 + 1) / 2.0, (z1 + z2 + 1) / 2.0);
    }

    /**
     * Rayo contra la caja. Devuelve {t, eje (0=x,1=y,2=z), lado (+1 = cara maxima, -1 = cara minima)} de la cara
     * que cruza el rayo (si el origen esta DENTRO de la caja, la cara por la que saldria), o null si no la toca.
     */
    public double[] ray(Vec3 origin, Vec3 dir, double maxT) {
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {dir.x, dir.y, dir.z};
        double tNear = Double.NEGATIVE_INFINITY;
        double tFar = Double.POSITIVE_INFINITY;
        int nearAxis = -1;
        int farAxis = -1;
        for (int a = 0; a < 3; a++) {
            double lo = min(a);
            double hi = max(a) + 1.0;
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < lo || o[a] > hi) {
                    return null;
                }
                continue;
            }
            double t1 = (lo - o[a]) / d[a];
            double t2 = (hi - o[a]) / d[a];
            double tn = Math.min(t1, t2);
            double tf = Math.max(t1, t2);
            if (tn > tNear) {
                tNear = tn;
                nearAxis = a;
            }
            if (tf < tFar) {
                tFar = tf;
                farAxis = a;
            }
        }
        if (tNear > tFar || tFar < 0) {
            return null;
        }
        if (tNear >= 0) {
            if (nearAxis < 0 || tNear > maxT) {
                return null;
            }
            return new double[] {tNear, nearAxis, d[nearAxis] > 0 ? -1 : 1};
        }
        if (farAxis < 0 || tFar > maxT) {
            return null;
        }
        return new double[] {tFar, farAxis, d[farAxis] > 0 ? 1 : -1};
    }

    /** La misma caja con una cara movida a "value" (lado +1 = maximo, -1 = minimo), o null si quedaria al reves. */
    public CpBox withBound(int axis, int side, int value) {
        int[] mn = {x1, y1, z1};
        int[] mx = {x2, y2, z2};
        if (side > 0) {
            mx[axis] = value;
        } else {
            mn[axis] = value;
        }
        if (mn[axis] > mx[axis]) {
            return null;
        }
        return new CpBox(number, mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
    }

    public CpBox withNumber(int n) {
        return new CpBox(n, x1, y1, z1, x2, y2, z2);
    }

    public void writeTo(List<Integer> out) {
        out.add(number);
        out.add(x1);
        out.add(y1);
        out.add(z1);
        out.add(x2);
        out.add(y2);
        out.add(z2);
    }

    public static CpBox read(List<Integer> in, int off) {
        return new CpBox(in.get(off), in.get(off + 1), in.get(off + 2), in.get(off + 3),
                in.get(off + 4), in.get(off + 5), in.get(off + 6));
    }

    /** La caja que apunta el rayo (la mas cercana), con su cara. Se usa igual en el cliente (resaltar) y en el servidor (estirar/borrar). */
    public record Hit(CpBox box, int axis, int side) {}

    public static Hit pick(List<CpBox> boxes, Vec3 eye, Vec3 look, double maxDist) {
        Hit best = null;
        double bestT = Double.MAX_VALUE;
        for (CpBox b : boxes) {
            double[] r = b.ray(eye, look, maxDist);
            if (r != null && r[0] < bestT) {
                bestT = r[0];
                best = new Hit(b, (int) r[1], (int) r[2]);
            }
        }
        return best;
    }

    /** El bloque "de la mira": a 4 bloques del ojo, en la direccion en que miras. */
    public static final double REACH = 4.0;

    public static BlockPos ghost(Vec3 eye, Vec3 look) {
        return new BlockPos((int) Math.floor(eye.x + look.x * REACH), (int) Math.floor(eye.y + look.y * REACH),
                (int) Math.floor(eye.z + look.z * REACH));
    }
}
