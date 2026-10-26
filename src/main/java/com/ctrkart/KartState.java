package com.ctrkart;

/** Estado de un kart. Vive solo en el cliente (como en vanilla, el cliente simula la montura). */
public final class KartState {
    public KartClass kartClass = KartClass.BALANCED;
    public int superTicks;   // Super Motor restante (lo fija el servidor al alimentar)
    public boolean flip;     // modelo girado 180 grados (etiqueta kart_flip)
    public float energy;     // ENERGIA 0..100: se conserva aunque te bajes del kart

    // --- estado dinamico (se reinicia al montar) ---
    public long lastTick = Long.MIN_VALUE;
    public float moveYaw;      // direccion real de avance
    public float bodyOffset;   // cuanto "cruza" el kart respecto a su avance al derrapar
    public double speed;       // bloques/tick (negativo = marcha atras)
    public boolean jumpHeld, backHeld, hopAir;
    public boolean drifting;
    public int driftDir;       // -1 izquierda, +1 derecha
    public int charge;         // posicion del medidor de derrape
    public int pending;        // turbos acertados en este derrape (max 3)
    public int airTicks;
    public double airMaxY;     // altura maxima alcanzada en el aire (para medir la caida)
    public int boostTicks;     // turbo de derrape restante (rojo/amarillo/azul)
    public int fireLevel;      // 0 sin fuego, 1 rojo, 2 amarillo, 3 azul
    public int chain;          // turbos encadenados sin que el fuego se apague
    public boolean sacred;     // Sacred Fire activo
    public int sacredTicks;    // duracion base restante del Sacred Fire (despues gasta energia)
    public boolean hasLast;    // para detectar choques reales contra paredes
    public double lastX, lastY, lastZ, appliedSpeed;

    public void resetDynamic() {
        moveYaw = 0;
        bodyOffset = 0;
        speed = 0;
        jumpHeld = false;
        backHeld = false;
        hopAir = false;
        drifting = false;
        driftDir = 0;
        charge = 0;
        pending = 0;
        airTicks = 0;
        airMaxY = 0;
        boostTicks = 0;
        fireLevel = 0;
        chain = 0;
        sacred = false;
        sacredTicks = 0;
        hasLast = false;
        appliedSpeed = 0;
    }
}
