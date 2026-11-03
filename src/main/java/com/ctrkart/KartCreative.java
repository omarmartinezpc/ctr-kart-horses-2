package com.ctrkart;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Anade objetos a una pestana del inventario creativo. La API de Fabric para esto cambio de nombre entre versiones
 * (ItemGroupEvents / CreativeModeTabEvents ...), asi que todo se hace por reflexion: primero se prueban los nombres
 * conocidos y, si ninguno sirve, se BUSCAN las clases de Fabric API relacionadas con pestanas del creativo.
 * El tipo del "oyente" se lee de la firma del metodo (Event&lt;ModifyOutput&gt;), asi que no se adivina.
 * Todo queda escrito en latest.log con el prefijo [ctrkart] Creativo.
 */
public final class KartCreative {
    private static final String[] KNOWN = {
        "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents",
        "net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents"
    };
    private static final Set<String> DELIVERED = new HashSet<>();

    private KartCreative() {}

    /** Devuelve true si se logro registrar el oyente (la entrega real ocurre cuando el juego arma la pestana). */
    public static boolean add(Object tabKey, Supplier<ItemStack> stack, String label) {
        Set<String> names = new LinkedHashSet<>(List.of(KNOWN));
        List<String> scanned = scan();
        names.addAll(scanned);
        for (String name : names) {
            if (tryClass(name, tabKey, stack, label)) {
                return true;
            }
        }
        System.err.println("[ctrkart] Creativo: no encontre como anadir \"" + label + "\". Clases vistas en Fabric API: " + scanned);
        return false;
    }

    /** Clases "...Events" de los modulos de Fabric API que tratan de pestanas / grupos del creativo. */
    private static List<String> scan() {
        List<String> out = new ArrayList<>();
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (!id.startsWith("fabric-") || !(id.contains("item-group") || id.contains("creative"))) {
                    continue;
                }
                for (Path root : mod.getRootPaths()) {
                    try (Stream<Path> walk = Files.walk(root)) {
                        walk.map(p -> root.relativize(p).toString().replace('\\', '/'))
                                .filter(p -> p.endsWith("Events.class") && !p.contains("$"))
                                .forEach(p -> out.add(p.substring(0, p.length() - 6).replace('/', '.')));
                    } catch (Exception ex) {
                        System.err.println("[ctrkart] Creativo: no pude leer " + id + ": " + ex);
                    }
                }
            }
        } catch (Throwable ex) {
            System.err.println("[ctrkart] Creativo: la busqueda fallo: " + ex);
        }
        return out;
    }

    private static boolean tryClass(String className, Object tabKey, Supplier<ItemStack> stack, String label) {
        try {
            Class<?> c = Class.forName(className);
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            List<Method> factories = new ArrayList<>();
            for (Method m : c.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(tabKey)
                        && eventClass.isAssignableFrom(m.getReturnType())) {
                    factories.add(m);
                }
            }
            // primero los "modify...Event" sin After/Before (los que entregan a la pestana normal)
            factories.sort((a, b) -> Integer.compare(rank(a.getName()), rank(b.getName())));
            for (Method factory : factories) {
                Class<?> listener = listenerType(factory);
                if (listener == null) {
                    continue;
                }
                Object event = factory.invoke(null, tabKey);
                Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, m, args) -> {
                    switch (m.getName()) {
                        case "toString":
                            return "ctrkart-creative-" + label;
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "equals":
                            return args != null && args.length == 1 && self == args[0];
                        default:
                            if (args != null && args.length >= 1 && args[0] != null) {
                                deliver(args[0], stack.get(), label);
                            }
                            return null;
                    }
                });
                eventClass.getMethod("register", Object.class).invoke(event, proxy);
                System.out.println("[ctrkart] Creativo: \"" + label + "\" registrado con " + className + "." + factory.getName());
                return true;
            }
        } catch (ClassNotFoundException ex) {
            // esta clase no existe en esta version: se prueba la siguiente
        } catch (InvocationTargetException ex) {
            System.err.println("[ctrkart] Creativo: " + className + " fallo: " + ex.getTargetException());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            System.err.println("[ctrkart] Creativo: " + className + " no sirvio: " + ex);
        }
        return false;
    }

    private static int rank(String name) {
        String n = name.toLowerCase();
        if (!n.startsWith("modify")) {
            return 3;
        }
        return n.contains("after") || n.contains("before") ? 2 : 0;
    }

    /** La interfaz del oyente, leida de la firma del metodo: Event<ModifyOutput> -> ModifyOutput. */
    private static Class<?> listenerType(Method factory) {
        Type t = factory.getGenericReturnType();
        if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> iface && iface.isInterface()) {
            return iface;
        }
        return null;
    }

    /** "output" es lo que recibe los objetos de la pestana; tiene accept(ItemStack) o accept(ItemStack, visibilidad). */
    private static void deliver(Object output, ItemStack stack, String label) {
        try {
            Method one = null;
            Method two = null;
            for (Method m : output.getClass().getMethods()) {
                if (!m.getName().equals("accept")) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == ItemStack.class) {
                    one = m;
                } else if (p.length == 2 && p[0] == ItemStack.class && p[1].isEnum()) {
                    two = m;
                }
            }
            if (one != null) {
                one.setAccessible(true);
                one.invoke(output, stack);
            } else if (two != null) {
                Object[] values = two.getParameterTypes()[1].getEnumConstants();
                Object visible = values[0];
                for (Object v : values) {
                    if (v.toString().equals("PARENT_AND_SEARCH_TABS")) {
                        visible = v;
                    }
                }
                two.setAccessible(true);
                two.invoke(output, stack, visible);
            } else {
                System.err.println("[ctrkart] Creativo: \"" + label + "\": el objeto de la pestana no tiene accept(ItemStack): " + output.getClass());
                return;
            }
            if (DELIVERED.add(label)) {
                System.out.println("[ctrkart] Creativo: \"" + label + "\" entregado a la pestana.");
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[ctrkart] Creativo: no pude anadir \"" + label + "\": " + ex);
        }
    }
}
