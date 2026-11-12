package com.ctrkart;

import net.minecraft.world.phys.Vec3;

/**
 * Nodo de RUTA de los bots: un punto (una celda de bloque) dentro de un checkpoint numerado, con un giro (como el bloque
 * de Rescate). Los nodos de un mismo checkpoint se recorren en el orden en que se colocaron; entre checkpoints, en el orden
 * de la carrera (0, 1, 2, ..., y otra vez 0). Dato guardado (ctrkart_cp.txt), no un bloque real.
 */
public record RouteNode(int x, int y, int z, int yaw) {
    /** Caja de una sola celda, para apuntarla con el rayo (igual que los checkpoints). */
    public CpBox box() {
        return new CpBox(CpBox.ROUTE_ID, x, y, z, x, y, z);
    }

    /** Centro de la celda (para ver en que checkpoint esta). */
    public Vec3 cellCenter() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    /** Donde tiene que pisar el kart: centro de la celda, a ras de suelo. */
    public Vec3 groundPoint() {
        return new Vec3(x + 0.5, y, z + 0.5);
    }
}
