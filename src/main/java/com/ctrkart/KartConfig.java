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
 *   manejo_clasico = true / false
 *
 * Tambien se cambia dentro del juego con el comando  /ctrkart manejoclasico  (alterna)  o
 * /ctrkart manejoclasico true|false.
 */
public final class KartConfig {
    /** MANEJO CLASICO: derrape mas recto que reacciona a hacia donde giras, y Sacred Fire manual con el click izquierdo. */
    public static volatile boolean classicHandling = false;

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
            classicHandling = Boolean.parseBoolean(p.getProperty("manejo_clasico", "false").trim());
        } catch (IOException ex) {
            System.err.println("[ctrkart] No pude leer " + f + ": " + ex);
        }
    }

    public static void save() {
        Path f = file();
        Properties p = new Properties();
        p.setProperty("manejo_clasico", Boolean.toString(classicHandling));
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
