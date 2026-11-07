package com.ctrkart;

/**
 * Bloque de RESCATE: un punto (una celda de bloque) dentro de un checkpoint. Cuando el kart es rescatado, aparece aqui
 * mirando hacia "yaw" (giro de Minecraft: 0 = sur, 90 = oeste, 180 = norte, -90 = este).
 */
public record RescuePoint(int x, int y, int z, int yaw) {
    /** Caja de una sola celda, para apuntarla con el rayo (igual que los checkpoints). */
    public CpBox box() {
        return new CpBox(CpBox.RESCUE_ID, x, y, z, x, y, z);
    }

    public boolean inside(CpBox area) {
        return x >= area.x1() && x <= area.x2() && y >= area.y1() && y <= area.y2() && z >= area.z1() && z <= area.z2();
    }

    /** Giro que corresponde a la cara lateral clicada (eje 0 = x, 2 = z; lado +1 = cara maxima). */
    public static int yawFor(int axis, int side) {
        if (axis == 0) {
            return side > 0 ? -90 : 90;
        }
        return side > 0 ? 0 : 180;
    }

    public static String yawName(int yaw) {
        return yaw == 0 ? "sur" : yaw == 90 ? "oeste" : yaw == 180 ? "norte" : "este";
    }

    /** Redondea un giro cualquiera a uno de los cuatro (0, 90, 180, -90). */
    public static int snap(float yawDeg) {
        float w = ((yawDeg % 360f) + 360f) % 360f; // 0..360
        int q = Math.round(w / 90f) % 4;
        return q == 0 ? 0 : q == 1 ? 90 : q == 2 ? 180 : -90;
    }
}
