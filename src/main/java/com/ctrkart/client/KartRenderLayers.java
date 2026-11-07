package com.ctrkart.client;

import com.ctrkart.KartBlocks;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.level.block.Block;

/**
 * Hace que los bloques TP se dibujen TRANSLUCIDOS (si no, el juego los pinta opacos). La API de Fabric para esto
 * (BlockRenderLayerMap) cambio de forma entre versiones, asi que se usa por reflexion: se busca la clase, el metodo
 * putBlock(bloque, capa) y la capa TRANSLUCENT. Si algo falla, el bloque se ve opaco pero funciona igual.
 * Todo queda en latest.log con el prefijo [ctrkart] Render.
 */
public final class KartRenderLayers {
    private KartRenderLayers() {}

    public static void register() {
        Block[] blocks = {KartBlocks.TP_ENTRY, KartBlocks.TP_EXIT};
        Set<String> names = new LinkedHashSet<>();
        names.add("net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap");
        names.add("net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap");
        names.addAll(scan());
        for (String name : names) {
            if (tryClass(name, blocks)) {
                return;
            }
        }
        System.err.println("[ctrkart] Render: no pude poner los bloques TP translucidos (se veran opacos). Clases vistas: " + scan());
    }

    private static List<String> scan() {
        List<String> out = new ArrayList<>();
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (!id.startsWith("fabric-") || !(id.contains("render") || id.contains("layer"))) {
                    continue;
                }
                for (Path root : mod.getRootPaths()) {
                    try (Stream<Path> walk = Files.walk(root)) {
                        walk.map(p -> root.relativize(p).toString().replace('\\', '/'))
                                .filter(p -> p.endsWith(".class") && !p.contains("$") && p.contains("BlockRenderLayer"))
                                .forEach(p -> out.add(p.substring(0, p.length() - 6).replace('/', '.')));
                    } catch (Exception ex) {
                        // se sigue con el siguiente modulo
                    }
                }
            }
        } catch (Throwable ex) {
            System.err.println("[ctrkart] Render: la busqueda fallo: " + ex);
        }
        return out;
    }

    private static boolean tryClass(String className, Block[] blocks) {
        try {
            Class<?> c = Class.forName(className);
            Object instance = null;
            try {
                Field f = c.getField("INSTANCE");
                instance = f.get(null);
            } catch (ReflectiveOperationException ignored) {
                // los metodos pueden ser estaticos
            }
            for (Method m : c.getMethods()) {
                if (!m.getName().equals("putBlock") || m.getParameterCount() != 2
                        || !Block.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    continue;
                }
                Object layer = translucent(m.getParameterTypes()[1]);
                if (layer == null) {
                    continue;
                }
                Object target = Modifier.isStatic(m.getModifiers()) ? null : instance;
                if (target == null && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                for (Block b : blocks) {
                    m.invoke(target, b, layer);
                }
                System.out.println("[ctrkart] Render: bloques TP translucidos con " + className + ".putBlock");
                return true;
            }
        } catch (ClassNotFoundException ex) {
            // no existe en esta version
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            System.err.println("[ctrkart] Render: " + className + " no sirvio: " + ex);
        }
        return false;
    }

    /** La capa "translucida": una constante TRANSLUCENT (enum) o un metodo estatico translucent(). */
    private static Object translucent(Class<?> layerType) {
        try {
            if (layerType.isEnum()) {
                for (Object v : layerType.getEnumConstants()) {
                    if (v.toString().equals("TRANSLUCENT")) {
                        return v;
                    }
                }
                return null;
            }
            Method m = layerType.getMethod("translucent");
            if (Modifier.isStatic(m.getModifiers())) {
                return m.invoke(null);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // esta capa no sirve
        }
        return null;
    }
}
