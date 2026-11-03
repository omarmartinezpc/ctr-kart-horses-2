package com.ctrkart;

/** Estado de un kart. Vive solo en el cliente (como en vanilla, el cliente simula la montura). */
public final class KartState {
    public KartClass kartClass = KartClass.BALANCED;
    public int superTicks;   // Super Motor restante (lo fija el servidor al alimentar)
    public boolean flip;     // modelo girado 180 grados (etiqueta kart_flip)
    public float energy;     // ENERGIA 0..100: se conserva aunque te bajes del kart
    public float steerAnim;  // direccion que lleva el volante, suavizada (-1 izquierda .. +1 derecha); la usan las ruedas delanteras
    public float wheelie;    // inclinacion del caballito en grados (0 = normal); la anima KartVisuals
    public int sacredEvents; // sube 1 con cada Sacred Fire asestado (el sonido del motor reacciona a esto)

    // --- estado dinamico (se reinicia al montar) ---
    public long lastTick;      // ultimo tick de CLIENTE (KartTracker.clientTick) en que corrio la fisica
    public boolean needsReset = true; // true = la proxima vez que corra la fisica se reinicia el estado dinamico (al montar)
    public float headYaw;      // hacia donde mira la cabeza del jugador (solo visual, no depende de la camara)
    public boolean headInit;   // false = la cabeza se coloca de golpe al montar
    public boolean driftGrounded; // este derrape ya toco el suelo: puede seguir cargando aunque estes en el aire (caidas)
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
    public boolean sacred;     // Sacred Fire activo
    public int sacredTier;     // nivel del Sacred Fire activo: 0 ninguno, 1 rojo, 2 amarillo, 3 amarillo potenciado, 4 azul
    public boolean uTurn;      // U-Turn activo (S en el aire)
    public int padOffTicks = 1000; // ticks desde que pisaste un bloque Sacred Pad (para que no se active en cada tick)
    public boolean chainBonus; // 3 Sacred Fire de derrape seguidos: un poco mas de velocidad mientras duren las reservas
    /** Memoria de los ultimos 3 Sacred Fire hechos con ESPACIO (true = se logro con el anterior aun activo). */
    public final boolean[] strikeChained = new boolean[3];
    public int strikeCount;    // cuantos hay guardados (0..3)
    // --- manejo clasico ---
    public boolean sacredReady; // la 3a barra esta llena: falta el click izquierdo para soltar el Sacred Fire
    public int driftHits;       // Sacred Fire encestados en este derrape (max 3)
    public int sacredWindow;    // ticks que quedan para hacer click con el Sacred Fire listo
    public boolean sacredFailed; // fallaste: no hay mas Sacred Fire en este derrape
    public int failSmokeTicks;  // humo negro en los escapes tras fallar
    public boolean attackHeld;  // click izquierdo del tick anterior (para detectar la pulsacion)
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
        driftGrounded = false;
        driftDir = 0;
        charge = 0;
        pending = 0;
        airTicks = 0;
        airMaxY = 0;
        boostTicks = 0;
        fireLevel = 0;
        sacred = false;
        sacredTier = 0;
        uTurn = false;
        padOffTicks = 1000;
        chainBonus = false;
        strikeCount = 0;
        sacredReady = false;
        steerAnim = 0f;
        driftHits = 0;
        sacredWindow = 0;
        sacredFailed = false;
        failSmokeTicks = 0;
        attackHeld = false;
        java.util.Arrays.fill(strikeChained, false);
        hasLast = false;
        appliedSpeed = 0;
    }
}
