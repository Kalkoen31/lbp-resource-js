package lbpjs;

import cwlib.enums.ResourceType;
import cwlib.structs.things.Thing;
import cwlib.types.data.GUID;
import cwlib.types.data.ResourceDescriptor;
import cwlib.types.data.SHA1;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.reflect.*;
import java.util.*;

/**
 * Fills cwlib objects with made-up values (no game data), so the tests cover
 * more than default-constructed resources. Values the binary format can't
 * hold are caught by the caller, which falls back to emptier objects.
 */
final class ResourceFiller
{
    private final Random random;
    private final int maxDepth;
    private final double fillChance;

    ResourceFiller(long seed, int maxDepth, double fillChance)
    {
        this.random = new Random(seed);
        this.maxDepth = maxDepth;
        this.fillChance = fillChance;
    }

    void fill(Object object, int depth)
    {
        for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field f : c.getDeclaredFields())
            {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || Modifier.isFinal(m)) continue;
                if (random.nextDouble() > fillChance) continue;
                try
                {
                    f.setAccessible(true);
                    Object value = make(f.getGenericType(), depth + 1);
                    if (value != null || !f.getType().isPrimitive()) f.set(object, value);
                }
                catch (Exception ignored) { }
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Object make(Type type, int depth)
    {
        Class<?> raw = type instanceof Class ? (Class<?>) type
            : type instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) type).getRawType() : Object.class;
        if (raw == int.class || raw == Integer.class) return random.nextInt(2000) - 100;
        if (raw == float.class || raw == Float.class)
            return random.nextInt(20) == 0 ? Float.NaN : (random.nextInt(4000) - 1000) / 8.0f;
        if (raw == boolean.class || raw == Boolean.class) return random.nextBoolean();
        if (raw == byte.class || raw == Byte.class) return (byte) random.nextInt(100);
        if (raw == short.class || raw == Short.class) return (short) random.nextInt(1000);
        if (raw == long.class || raw == Long.class) return (long) random.nextInt(100000);
        if (raw == double.class || raw == Double.class) return random.nextInt(1000) / 4.0;
        if (raw == char.class || raw == Character.class) return (char) ('a' + random.nextInt(26));
        if (raw == String.class) return "str" + random.nextInt(1000) + (random.nextInt(5) == 0 ? " é中\"\\" : "");
        if (raw.isEnum())
        {
            Object[] values = raw.getEnumConstants();
            return values[random.nextInt(values.length)];
        }
        if (raw == GUID.class) return new GUID(1 + random.nextInt(100000));
        if (raw == SHA1.class)
        {
            byte[] hash = new byte[20];
            random.nextBytes(hash);
            return new SHA1(hash);
        }
        if (raw == ResourceDescriptor.class)
        {
            ResourceType[] types = { ResourceType.PLAN, ResourceType.MESH, ResourceType.TEXTURE, ResourceType.GFX_MATERIAL };
            ResourceType t = types[random.nextInt(types.length)];
            return random.nextBoolean() ? new ResourceDescriptor(new GUID(1 + random.nextInt(100000)), t)
                : new ResourceDescriptor((SHA1) make(SHA1.class, depth), t);
        }
        if (raw == Vector2f.class) return new Vector2f(f(), f());
        if (raw == Vector3f.class) return new Vector3f(f(), f(), f());
        if (raw == Vector4f.class) return new Vector4f(f(), f(), f(), f());
        if (raw == Matrix4f.class) return new Matrix4f().translate(f(), f(), f()).scale(1 + random.nextInt(3));
        if (depth > maxDepth) return null;
        if (raw == Thing.class) return null; // thing graphs have rules of their own
        if (raw.isArray())
        {
            int n = random.nextInt(4);
            Object array = Array.newInstance(raw.getComponentType(), n);
            for (int i = 0; i < n; ++i)
            {
                Object v = make(raw.getComponentType(), depth + 1);
                if (v == null && raw.getComponentType().isPrimitive()) return null;
                Array.set(array, i, v);
            }
            return array;
        }
        if (List.class.isAssignableFrom(raw) || raw == HashSet.class)
        {
            Type element = ((ParameterizedType) type).getActualTypeArguments()[0];
            Collection list = raw == HashSet.class ? new HashSet<>() : new ArrayList<>();
            int n = random.nextInt(3);
            for (int i = 0; i < n; ++i)
            {
                Object v = make(element, depth + 1);
                if (v != null) list.add(v);
            }
            return list;
        }
        if (raw == EnumSet.class)
        {
            Class e = (Class) ((ParameterizedType) type).getActualTypeArguments()[0];
            EnumSet set = EnumSet.noneOf(e);
            for (Object v : e.getEnumConstants()) if (random.nextInt(3) == 0) set.add(v);
            return set;
        }
        if (Map.class.isAssignableFrom(raw)) return null;
        if (!raw.getName().startsWith("cwlib.") || raw.isInterface() || Modifier.isAbstract(raw.getModifiers()))
            return null;
        try
        {
            Constructor<?> constructor = raw.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object value = constructor.newInstance();
            fill(value, depth);
            return value;
        }
        catch (Exception ex) { return null; }
    }

    private float f() { return (random.nextInt(800) - 400) / 4.0f; }
}
