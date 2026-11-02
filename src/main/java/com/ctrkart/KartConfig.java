package com.ctrkart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Configuracion del mod. Se guarda en  .minecraft/config/ctrkart.properties
 *
 *   manejo_clasico = true / false   (por defecto: true)
 *   camara_ctr     = true / false   (por defecto: false) camara fija trasera estilo CTR Nitro-Fueled
 *
 * Tambien se cambia dentro del juego con el comando  /ctrkart manejoclasico  (alterna)  o
 * /ctrkart manejoclasico true|false.
 */
public final class KartConfig {
    /** Version del archivo de config. Si el archivo es mas viejo, se aplican los valores por defecto nuevos. */
    private static final int CONFIG_VERSION = 2;

    /** MANEJO CLASICO: derrape mas recto que reacciona a hacia donde giras, y Sacred Fire manual con el click izquierdo. */
    public static volatile boolean classicHandling = true;

    /** CAMARA CTR: tercera persona fija detras del kart, que sigue su trayectoria. */
    public static volatile boolean ctrCamera = false;

    private KartConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ctrkart.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) {
            save(); // crea el archivo con los valores por defecto
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
            int version = 1;
            try {
                version = Integer.parseInt(p.getProperty("version", "1").trim());
            } catch (NumberFormatException ignored) {
                // archivo sin version: se trata como el antiguo
            }
            ctrCamera = Boolean.parseBoolean(p.getProperty("camara_ctr", "false").trim());
            if (version < CONFIG_VERSION) {
                classicHandling = true; // el manejo clasico pasa a ser el modo por defecto
                save();
            } else {
                classicHandling = Boolean.parseBoolean(p.getProperty("manejo_clasico", "true").trim());
            }
        } catch (IOException ex) {
            System.err.println("[ctrkart] No pude leer " + f + ": " + ex);
        }
    }

    public static void save() {
        Path f = file();
        Properties p = new Properties();
        p.setProperty("version", Integer.toString(CONFIG_VERSION));
        p.setProperty("manejo_clasico", Boolean.toString(classicHandling));
        p.setProperty("camara_ctr", Boolean.toString(ctrCamera));
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "CTR Kart Horses - manejo_clasico: true = derrape recto y Sacred Fire manual con click izquierdo");
            }
        } catch (IOException ex) {
            System.err.println("[ctrkart] No pude guardar " + f + ": " + ex);
        }
    }
}
