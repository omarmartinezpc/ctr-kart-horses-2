# CTR Kart Horses (Fabric, Minecraft 26.3)

Los caballos con la etiqueta `kart` se conducen como un kart de Crash Team Racing Nitro-Fueled.

## Uso
1. Ensilla y monta un caballo (o burro, mula, etc.).
2. Ponle la etiqueta: `/tag @e[type=horse,limit=1,sort=nearest] add kart`
   Clases opcionales (una sola, ademas de `kart`): `kart_speed`, `kart_accel`, `kart_turn`, `kart_big`.
3. Quitar el modo kart: `/tag <selector> remove kart`

## Controles
- W / S: acelerar / frenar y marcha atras. A / D: girar (el kart ya no sigue la camara).
- ESPACIO: hop. Mantenerlo mientras giras (A/D) en el aire = derrape.
- Durante el derrape la barra se llena sola (0,35 s por barra): 1a barra = turbo rojo, 2a = amarillo,
  3a = SACRED FIRE al instante. Al soltar ESPACIO con 1 o 2 barras se cobra el turbo. No hay que pulsar S.
- Alimentar al kart = Super Motor + energia.

## Manejo clasico (ajuste, ACTIVADO por defecto)
Se activa con `/ctrkart manejoclasico` (alterna; tambien `/ctrkart manejoclasico true` o `false`) o poniendo
`manejo_clasico=true/false` en `.minecraft/config/ctrkart.properties`. Cambia dos cosas:
- **Derrape mas recto**: ya no te lleva tanto hacia los lados. Reacciona a hacia donde giras: si giras hacia el lado
  en que empezaste el derrape, curva mas; si giras hacia el lado contrario, el derrape queda casi recto.
- **Sacred Fire manual**: al llenarse la 3a barra el Sacred Fire queda listo (aviso en pantalla y sonido) y hay que
  pulsar el **click izquierdo** en los siguientes 0,6 s. Si aciertas, se activa y la barra vuelve a llenarse: puedes
  hacer otro en el mismo derrape (maximo 3, contando el primero). Si no pulsas a tiempo, ese Sacred Fire se pierde,
  no puedes hacer mas en ese derrape, suena `sacred_fail.ogg` y los escapes sueltan humo negro 2,5 s
  Fallar el Sacred Fire no da ninguna recompensa (tampoco turbo amarillo).

## Humo de los escapes
Humo blanco en los escapes con el kart en reposo o en marcha mientras no haya Sacred Fire activo; humo negro si
fallas el Sacred Fire del manejo clasico.

## Modelo de kart
Con la etiqueta `kart` el caballo se oculta y se dibuja un kart low poly (cajas planas, estilo PS1) con dos
escapes traseros; el fuego y las particulas salen por esos escapes. El caballo se reduce a escala 0.6 para que
el jugador vaya sentado a la altura del kart (al quitar `kart` vuelve a escala 1.0).
- Si el kart te aparece mirando hacia atras: `/tag <selector> add kart_flip` (y `remove kart_flip` para quitarlo).
- El modelo esta en `src/main/resources/assets/ctrkart/models/item/kart.json` y los colores en
  `textures/item/kart.png` (una celda de 2x2 px por color).

## Mecanicas
- Velocidad: la base es siempre menor que la de cualquier turbo. Manda la velocidad MAS ALTA entre las fuentes
  activas (turbo, Sacred Fire, Super Motor): si ya vas mas rapido que lo que da una fuente, se respeta.
- Turbos de derrape: nivel 1 (rojo) y 2 (amarillo), duracion fija.
- Sacred Fire: 3a barra del derrape (o caida alta). Velocidad maxima mientras queden reservas.
  Cada Sacred Fire hecho con ESPACIO acumula +25 de energia (se suma, no solo se mantiene) y suena uno de
  tus 3 audios (alternando, sin ningun otro sonido de Minecraft).
- Cadena: el mod recuerda tus ultimos 3 Sacred Fire hechos con ESPACIO (las caidas no cuentan). Si los 3 fueron
  seguidos (cada uno con el anterior aun activo), tienes un poco mas de velocidad (x1.62 en vez de x1.55)
  hasta que se acaben las reservas. Se ve como estrellas junto a "SACRED FIRE".
- ENERGIA / RESERVAS (barra en la barra de accion, 0-200%): se drenan SIEMPRE (10 s por cada 100), sin pausas; de 101% a 200% se gastan 25% mas rapido (la barra se pinta encima en un azul mas oscuro).
  El Sacred Fire dura mientras queden reservas. Se obtienen de:
  - Asestar un Sacred Fire con ESPACIO: +25 cada vez.
  - Caida alta: de 3 bloques (+10) a 24 o mas (+100). Ademas activa la velocidad de Sacred Fire.
  - Alimentar al kart: manzana +15, zanahoria dorada +25, manzana dorada +40, manzana dorada encantada +100,
    zanahoria +12, azucar +10, trigo +8. Ademas da Super Motor.
- Subir bloques/escalones no te quita velocidad; solo un choque frontal real la reduce.

## Compilar
Necesitas JDK 25. Copia `gradlew`, `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar` desde el generador oficial
(https://fabricmc.net/develop/template/) o ejecuta `gradle wrapper`, y luego `./gradlew build`.
Compara tambien las versiones de `gradle.properties` con las que da el generador.

## Si no compila (no pude compilarlo: sin acceso a la red ni a los jar de Minecraft)
Sitios mas probables a ajustar, todos puntuales:
- `AbstractHorse`: paquete `...animal.equine` o `...animal.horse` (3 archivos).
- `KartClass.idOf`: nombre de `Entity.getTags()`.
- `LivingEntityAccessor`: nombre del campo `jumping`.
- `Identifier` (antes `ResourceLocation`), `PayloadTypeRegistry.playS2C()` (puede llamarse `clientboundPlay()`).
- Mixins: `getRiddenInput` y `tickRidden` en `AbstractHorse`.

## Sonido del motor
Es un bucle continuo, reconstruido a partir del audio de referencia de CTR Nitro-Fueled (sintetizado, no es el audio
original). Suena mientras alguien va montado. El tono sube con la velocidad (reposo 0.62 -> marcha ~0.83 -> maximo 1.0
= la nota estable de ~172 Hz que se oye al final del audio de referencia -> con turbos hasta ~1.2).
Al asestar un Sacred Fire el motor da un salto de +16 % que decae en ~0,5 s, y mientras cargas el derrape baja un ~7 %.
El audio esta en `src/main/resources/assets/ctrkart/sounds/kart_engine.ogg` (1 s, mono, repetible sin cortes);
si pones otro, que sea un bucle largo de motor a velocidad maxima y mono (el mod lo sube y baja de tono).

## Objeto "Kart" (creativo)
En el inventario creativo, pestana de huevos de aparicion, hay un objeto "Kart". Click derecho sobre un bloque y
aparece un caballo domado, ensillado y con la etiqueta `kart`.

## Camara y cabeza
- **Cabeza**: mientras conduces, la cabeza del jugador solo puede girar 50 grados a cada lado respecto al kart
  (`HEAD_LIMIT_DEG` en `KartCamera.java`).
- **Camara CTR** (ajuste, desactivado por defecto): `/ctrkart camara` (alterna; tambien `true` o `false`) o
  `camara_ctr=true` en `ctrkart.properties`. Al montar pasa a tercera persona y la camara se queda fija detras
  del kart, siguiendo su trayectoria con un poco de retraso; al bajar se devuelve tu camara anterior.
