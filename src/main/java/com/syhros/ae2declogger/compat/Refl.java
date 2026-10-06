package com.syhros.ae2declogger.compat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reflection by name for the mod integrations. The mods are not on the compile classpath (each changes its internals
 * between Minecraft versions), so classes and members are looked up by name and cached. A missing name throws
 * {@link ReflectiveOperationException}, which switches that one integration off (see {@link Machines}).
 */
public final class Refl {

    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> IS_A = new ConcurrentHashMap<>();

    private Refl() {
    }

    /** The first of the named classes that exists, or null. */
    @Nullable
    public static Class<?> cls(String... names) {
        for (String name : names) {
            Class<?> c = CLASSES.computeIfAbsent(name, n -> {
                try {
                    return Optional.of(Class.forName(n, false, Refl.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    return Optional.empty();
                }
            }).orElse(null);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** True if {@code o} is an instance of a class or interface with one of these names (no class loading). */
    public static boolean isA(@Nullable Object o, String... classNames) {
        if (o == null) {
            return false;
        }
        for (String name : classNames) {
            Class<?> type = o.getClass();
            if (IS_A.computeIfAbsent(type.getName() + ">" + name, k -> inherits(type, name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean inherits(Class<?> type, String name) {
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            if (c.getName().equals(name)) {
                return true;
            }
            if (c.getSuperclass() != null) {
                todo.add(c.getSuperclass());
            }
            todo.addAll(List.of(c.getInterfaces()));
        }
        return false;
    }

    /** A method by name and parameter count, public or not, declared on the class or any supertype. */
    @Nullable
    public static Method method(Class<?> type, String name, int params) {
        return METHODS.computeIfAbsent(type.getName() + "#" + name + "/" + params, k -> {
            Deque<Class<?>> todo = new ArrayDeque<>();
            Set<Class<?>> seen = new HashSet<>();
            todo.add(type);
            while (!todo.isEmpty()) {
                Class<?> c = todo.poll();
                if (c == null || !seen.add(c)) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == params && !m.isBridge()) {
                        try {
                            m.setAccessible(true);
                            return Optional.of(m);
                        } catch (RuntimeException e) {
                            return Optional.empty();
                        }
                    }
                }
                if (c.getSuperclass() != null) {
                    todo.add(c.getSuperclass());
                }
                todo.addAll(List.of(c.getInterfaces()));
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** True if the object's class has this method (any visibility). */
    public static boolean has(Object target, String name, int params) {
        return method(target.getClass(), name, params) != null;
    }

    /** Calls a method by name (matched on argument count). */
    @Nullable
    public static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method m = method(target.getClass(), name, args.length);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name + "/" + args.length);
        }
        return invoke(m, Modifier.isStatic(m.getModifiers()) ? null : target, args);
    }

    /** Calls a static method of the named class. */
    @Nullable
    public static Object callStatic(String className, String name, Object... args) throws ReflectiveOperationException {
        Class<?> c = cls(className);
        if (c == null) {
            throw new ClassNotFoundException(className);
        }
        Method m = method(c, name, args.length);
        if (m == null) {
            throw new NoSuchMethodException(className + "#" + name + "/" + args.length);
        }
        return invoke(m, null, args);
    }

    private static Object invoke(Method m, @Nullable Object target, Object... args) throws ReflectiveOperationException {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw e;
        }
    }

    public static boolean bool(Object target, String name, Object... args) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(target, name, args));
    }

    public static long num(Object target, String name, Object... args) throws ReflectiveOperationException {
        Object v = call(target, name, args);
        return v instanceof Number n ? n.longValue() : 0;
    }

    @Nullable
    private static Field fieldOf(Class<?> type, String name) {
        return FIELDS.computeIfAbsent(type.getName() + "@" + name, k -> {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getName().equals(name)) {
                        try {
                            f.setAccessible(true);
                            return Optional.of(f);
                        } catch (RuntimeException e) {
                            return Optional.empty();
                        }
                    }
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** A field's value by name (any visibility, declared on the class or a superclass). */
    @Nullable
    public static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = fieldOf(target.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldException(target.getClass().getName() + "@" + name);
        }
        return f.get(target);
    }

    /** A field's value, or null if the class has no such field. */
    @Nullable
    public static Object fieldOrNull(Object target, String name) throws ReflectiveOperationException {
        Field f = fieldOf(target.getClass(), name);
        return f == null ? null : f.get(target);
    }

    /** The first non-null field whose type's simple name is {@code typeSimpleName} (or extends a class so named). */
    @Nullable
    public static Object fieldOfType(Object target, String typeSimpleName) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                for (Class<?> t = f.getType(); t != null; t = t.getSuperclass()) {
                    if (t.getSimpleName().equals(typeSimpleName)) {
                        f.setAccessible(true);
                        Object v = f.get(target);
                        if (v != null) {
                            return v;
                        }
                        break;
                    }
                }
            }
        }
        return null;
    }

    /** The name of the public static field of the object's own class that holds this exact object (enum-like). */
    @Nullable
    public static String constName(Object value) throws IllegalAccessException {
        for (Field f : value.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.get(null) == value) {
                return f.getName();
            }
        }
        return value instanceof Enum<?> e ? e.name() : null;
    }

    /** A public static constant (enum value or static final field) by class and name. */
    public static Object enumConst(String className, String constant) throws ReflectiveOperationException {
        Class<?> c = cls(className);
        if (c == null) {
            throw new ClassNotFoundException(className);
        }
        return c.getField(constant).get(null);
    }

    /** Loaded block entities within {@code radius} blocks of {@code center} that match, without loading chunks. */
    public static List<BlockEntity> nearby(ServerLevel level, BlockPos center, int radius, Predicate<BlockEntity> test) {
        List<BlockEntity> out = new ArrayList<>();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (Math.abs(p.getX() - center.getX()) <= radius && Math.abs(p.getY() - center.getY()) <= radius
                            && Math.abs(p.getZ() - center.getZ()) <= radius && test.test(be)) {
                        out.add(be);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Names a reader needs, as "a.B|a.C" (any of these classes), "a.B#method" or "a.B@field". Returns the ones that
     * cannot be found, for the self-test.
     */
    public static List<String> missing(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String spec : names) {
            String classes = spec;
            String member = null;
            boolean isField = false;
            int hash = spec.indexOf('#');
            int at = spec.indexOf('@');
            if (hash > 0) {
                classes = spec.substring(0, hash);
                member = spec.substring(hash + 1);
            } else if (at > 0) {
                classes = spec.substring(0, at);
                member = spec.substring(at + 1);
                isField = true;
            }
            Class<?> c = cls(classes.split("\\|"));
            if (c == null) {
                out.add(spec);
                continue;
            }
            if (member == null) {
                continue;
            }
            boolean found = false;
            for (String alt : member.split("\\|")) {
                found |= isField ? fieldOf(c, alt) != null : anyArity(c, alt);
            }
            if (!found) {
                out.add(spec);
            }
        }
        return out;
    }

    private static boolean anyArity(Class<?> c, String name) {
        for (int n = 0; n <= 4; n++) {
            if (method(c, name, n) != null) {
                return true;
            }
        }
        return false;
    }
}
