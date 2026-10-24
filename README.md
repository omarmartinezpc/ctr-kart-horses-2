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
- En el derrape, el medidor de la barra de accion da vueltas; pulsa S (un toque) cuando esté en la zona verde
  = turbo perfecto. Hasta 3 por derrape; al tercero (o al soltar ESPACIO) se apilan en un turbo.
- Hang time: caer desde mas de ~0,6 s en el aire da un turbo al aterrizar.
- Alimentar al kart (manzana, zanahoria dorada, manzana dorada, azucar, trigo, zanahoria) = Super Motor.

## Mecanicas
- Reservas: cada turbo suma tiempo a un banco (max 8 s); mientras haya reservas hay turbo.
- Fuego: rojo (1 turbo) -> amarillo (2) -> azul (3). Sacred Fire: 3 turbos encadenados sin que el fuego se apague;
  da la velocidad mas alta y gasta reservas a mitad de ritmo.
- Super Motor: la velocidad maxima sube a velocidad de turbo durante unos segundos.

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
