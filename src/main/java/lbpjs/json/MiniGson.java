package lbpjs.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.internal.LinkedTreeMap;
import cwlib.io.gson.ByteArraySerializer;
import cwlib.io.gson.GsonRevision;
import cwlib.io.gson.GsonRevisions;
import cwlib.io.gson.Matrix4fSerializer;
import cwlib.io.gson.Vector2fSerializer;
import cwlib.io.gson.Vector3fSerializer;
import cwlib.io.gson.Vector4fSerializer;
import cwlib.types.data.Revision;
import cwlib.util.GsonUtils;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A reflection-light stand-in for the Gson instance that cwlib builds in
 * {@code cwlib.util.GsonUtils.GetGson()}, for runtimes (TeaVM) where Gson's
 * own object binding can't run because it needs generic superclass and other
 * reflection that isn't there.
 * <p>
 * It reproduces Gson 2.8.9's behaviour for that configuration: pretty much
 * {@code ReflectiveTypeAdapterFactory}, {@code TypeAdapterRuntimeTypeWrapper},
 * the collection, map (complex keys on), array, enum and primitive adapters,
 * {@code @JsonAdapter} on classes and fields, the type adapters cwlib registers,
 * serializeNulls, special floating point values, and cwlib's revision based
 * exclusion strategy ({@code @GsonRevision}/{@code @GsonRevisions}) on serialization.
 * cwlib's own {@code JsonSerializer}/{@code JsonDeserializer} classes are used
 * unchanged: this class is the context they get.
 * <p>
 * Works on trees ({@link JsonElement}) rather than streams, which gives the same
 * result. It only needs {@link Class#getDeclaredFields()}, {@link Field#getGenericType()},
 * field annotations, field get/set and no-argument constructors.
 * <p>
 * One instance corresponds to one Gson instance (GsonUtils makes a new one per
 * call), so use a new one per conversion.
 */
public final class MiniGson implements JsonSerializationContext, JsonDeserializationContext
{
    /** Base of all adapters, the tree version of Gson's {@code TypeAdapter}. */
    abstract static class Adapter
    {
        abstract JsonElement write(Object value);

        abstract Object read(JsonElement json);

        /** True only for the equivalent of {@code ReflectiveTypeAdapterFactory.Adapter}. */
        boolean isReflective() { return false; }
    }

    /** Gson's {@code FutureTypeAdapter}: handed out while a type's adapter is being built. */
    static final class FutureAdapter extends Adapter
    {
        Adapter delegate;

        private Adapter delegate()
        {
            if (delegate == null) throw new IllegalStateException();
            return delegate;
        }

        @Override JsonElement write(Object value) { return delegate().write(value); }

        @Override Object read(JsonElement json) { return delegate().read(json); }
    }

    /** Which factory to skip, for Gson's {@code getDelegateAdapter}. */
    private enum Skip { NONE, REGISTERED, CLASS_ANNOTATION }

    private final Map<Class<?>, Object> registered = new HashMap<>();
    private final Map<TypeKey, Adapter> cache = new HashMap<>();
    private final Map<TypeKey, FutureAdapter> ongoing = new HashMap<>();

    public MiniGson()
    {
        // GsonUtils.GetGson(): registerTypeAdapter(...) for these exact types.
        registered.put(Vector2f.class, new Vector2fSerializer());
        registered.put(Vector3f.class, new Vector3fSerializer());
        registered.put(Vector4f.class, new Vector4fSerializer());
        registered.put(Matrix4f.class, new Matrix4fSerializer());
        registered.put(byte[].class, new ByteArraySerializer());
    }

    // ---- entry points, matching cwlib.util.GsonUtils -----------------------------------

    /** {@code GsonUtils.toJSON(object, revision)}, as a tree. */
    public static JsonElement toTree(Object object, Revision revision)
    {
        GsonUtils.REVISION = revision;
        GsonUtils.THINGS.clear();
        GsonUtils.UNIQUE_THINGS.clear();
        return new MiniGson().serialize(object);
    }

    /** {@code GsonUtils.fromJSON(json, clazz)}, from a tree. */
    public static <T> T fromTree(JsonElement json, Class<T> clazz)
    {
        GsonUtils.THINGS.clear();
        GsonUtils.UNIQUE_THINGS.clear();
        return new MiniGson().deserialize(json, clazz);
    }

    // ---- contexts handed to cwlib's serializers ------------------------------------------

    /** {@code Gson.toJsonTree(src)}: uses the runtime class. */
    @Override
    public JsonElement serialize(Object src)
    {
        if (src == null) return JsonNull.INSTANCE;
        return serialize(src, src.getClass());
    }

    /** {@code Gson.toJsonTree(src, typeOfSrc)}. */
    @Override
    public JsonElement serialize(Object src, Type typeOfSrc)
    {
        JsonElement out = getAdapter(typeOfSrc).write(src);
        return out == null ? JsonNull.INSTANCE : out;
    }

    /** {@code Gson.fromJson(json, typeOfT)}. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(JsonElement json, Type typeOfT) throws JsonParseException
    {
        if (json == null) return null;
        try { return (T) getAdapter(typeOfT).read(json); }
        catch (IllegalStateException ex) { throw new JsonSyntaxException(ex); }
    }

    // ---- adapter lookup (Gson.getAdapter) ---------------------------------------------------

    Adapter getAdapter(Type type)
    {
        TypeKey key = new TypeKey(type);
        Adapter cached = cache.get(key);
        if (cached != null) return cached;
        FutureAdapter ongoingCall = ongoing.get(key);
        if (ongoingCall != null) return ongoingCall;

        FutureAdapter call = new FutureAdapter();
        ongoing.put(key, call);
        try
        {
            Adapter adapter = create(type, Skip.NONE);
            call.delegate = adapter;
            cache.put(key, adapter);
            return adapter;
        }
        finally { ongoing.remove(key); }
    }

    /** The factory chain of a Gson instance, in Gson's order. */
    private Adapter create(Type type, Skip skip)
    {
        Class<?> raw = rawType(type);

        // TypeAdapters.JSON_ELEMENT_FACTORY, ObjectTypeAdapter.FACTORY
        if (JsonElement.class.isAssignableFrom(raw)) return JSON_ELEMENT;
        if (raw == Object.class) return new ObjectAdapter();

        // Excluder: anonymous and local classes become null.
        if (isAnonymousOrNonStaticLocal(raw)) return EXCLUDED;

        // registerTypeAdapter(...) (exact type match)
        if (skip == Skip.NONE && type instanceof Class<?> && registered.containsKey(raw))
        {
            Object handler = registered.get(raw);
            return new TreeAdapter(handler, type, () -> create(type, Skip.REGISTERED));
        }

        // Built in adapters
        Adapter builtin = builtin(raw);
        if (builtin != null) return builtin;
        if (type instanceof GenericArrayType || raw.isArray())
        {
            Type component = type instanceof GenericArrayType
                ? ((GenericArrayType) type).getGenericComponentType()
                : raw.getComponentType();
            return new ArrayAdapter(component, getAdapter(component));
        }
        if (raw == Class.class) return CLASS;

        // CollectionTypeAdapterFactory, MapTypeAdapterFactory
        if (Collection.class.isAssignableFrom(raw))
        {
            Type element = typeArgument(type, 0, 1);
            return new CollectionAdapter(type, raw, element, runtimeWrapper(getAdapter(element), element));
        }
        if (Map.class.isAssignableFrom(raw))
        {
            Type keyType = typeArgument(type, 0, 2);
            Type valueType = typeArgument(type, 1, 2);
            Adapter keyAdapter = (keyType == boolean.class || keyType == Boolean.class)
                ? BOOLEAN_AS_STRING : getAdapter(keyType);
            return new MapAdapter(type, raw, keyType,
                runtimeWrapper(keyAdapter, keyType),
                runtimeWrapper(getAdapter(valueType), valueType));
        }

        // JsonAdapterAnnotationTypeAdapterFactory (class annotation)
        if (skip != Skip.CLASS_ANNOTATION)
        {
            Object handler = JsonAdapterTable.forClass(raw);
            if (handler != null)
                return fromAnnotation(handler, type, () -> create(type, Skip.CLASS_ANNOTATION));
        }

        // TypeAdapters.ENUM_FACTORY
        if (Enum.class.isAssignableFrom(raw) && raw != Enum.class)
        {
            Class<?> enumClass = raw.isEnum() ? raw : raw.getSuperclass();
            return new EnumAdapter(enumClass);
        }

        // ReflectiveTypeAdapterFactory
        if (raw.isInterface() || raw.isPrimitive())
            return new ReflectiveAdapter(raw, new LinkedHashMap<>());
        return new ReflectiveAdapter(raw, boundFields(type, raw));
    }

    /** JsonAdapterAnnotationTypeAdapterFactory.getTypeAdapter for a serializer/deserializer instance. */
    private Adapter fromAnnotation(Object instance, Type type, Supplier delegate)
    {
        if (!(instance instanceof JsonSerializer) && !(instance instanceof JsonDeserializer))
            throw new IllegalArgumentException("Only JsonSerializer/JsonDeserializer @JsonAdapter classes are supported here, got " + instance);
        return new TreeAdapter(instance, type, delegate);
    }

    interface Supplier { Adapter get(); }

    // ---- TypeAdapterRuntimeTypeWrapper -----------------------------------------------------

    Adapter runtimeWrapper(Adapter delegate, Type declared)
    {
        return new Adapter()
        {
            @Override
            JsonElement write(Object value)
            {
                Adapter chosen = delegate;
                Type runtimeType = declared;
                if (value != null && (declared == Object.class || declared instanceof TypeVariable<?> || declared instanceof Class<?>))
                    runtimeType = value.getClass();
                if (runtimeType != declared)
                {
                    Adapter runtimeAdapter = getAdapter(runtimeType);
                    if (!runtimeAdapter.isReflective()) chosen = runtimeAdapter;
                    else if (!delegate.isReflective()) chosen = delegate;
                    else chosen = runtimeAdapter;
                }
                return chosen.write(value);
            }

            @Override
            Object read(JsonElement json) { return delegate.read(json); }
        };
    }

    // ---- reflective adapter ------------------------------------------------------------------

    final class BoundField
    {
        final String name;
        final Field field;
        final boolean serialized;
        final boolean deserialized;
        final Type fieldType;
        final boolean isPrimitive;
        final boolean jsonAdapterPresent;
        final Adapter adapter;

        BoundField(String name, Field field, boolean serialized, boolean deserialized, Type fieldType)
        {
            this.name = name;
            this.field = field;
            this.serialized = serialized;
            this.deserialized = deserialized;
            this.fieldType = fieldType;
            this.isPrimitive = fieldType instanceof Class<?> && ((Class<?>) fieldType).isPrimitive();
            Object handler = JsonAdapterTable.forField(field);
            Adapter mapped = handler != null
                ? fromAnnotation(handler, fieldType, () -> create(fieldType, Skip.CLASS_ANNOTATION))
                : null;
            this.jsonAdapterPresent = mapped != null;
            this.adapter = mapped != null ? mapped : getAdapter(fieldType);
        }

        Object get(Object instance)
        {
            try { return field.get(instance); }
            catch (IllegalAccessException ex) { throw new AssertionError(ex); }
        }

        boolean writeField(Object instance)
        {
            if (!serialized) return false;
            return get(instance) != instance; // avoid recursion
        }

        JsonElement write(Object instance)
        {
            Object value = get(instance);
            Adapter t = jsonAdapterPresent ? adapter : runtimeWrapper(adapter, fieldType);
            return t.write(value);
        }

        void read(Object instance, JsonElement json)
        {
            Object value = adapter.read(json);
            if (value != null || !isPrimitive)
            {
                try { field.set(instance, value); }
                catch (IllegalAccessException ex) { throw new AssertionError(ex); }
            }
        }
    }

    private LinkedHashMap<String, BoundField> boundFields(Type declaredType, Class<?> raw)
    {
        LinkedHashMap<String, BoundField> result = new LinkedHashMap<>();
        Type type = declaredType;
        while (raw != Object.class && raw != null)
        {
            for (Field field : raw.getDeclaredFields())
            {
                boolean serialize = !excludeField(field, true);
                boolean deserialize = !excludeField(field, false);
                if (!serialize && !deserialize) continue;
                field.setAccessible(true);
                Type fieldType = resolve(field.getGenericType());
                BoundField previous = null;
                List<String> names = fieldNames(field);
                for (int i = 0; i < names.size(); ++i)
                {
                    String name = names.get(i);
                    if (i != 0) serialize = false; // only serialize the default name
                    BoundField bound = new BoundField(name, field, serialize, deserialize, fieldType);
                    BoundField replaced = result.put(name, bound);
                    if (previous == null) previous = replaced;
                }
                if (previous != null)
                    throw new IllegalArgumentException(typeKey(declaredType) + " declares multiple JSON fields named " + previous.name);
            }
            raw = raw.getSuperclass();
        }
        return result;
    }

    private static List<String> fieldNames(Field field)
    {
        List<String> names = new ArrayList<>();
        SerializedName annotation = field.getAnnotation(SerializedName.class);
        if (annotation == null) names.add(field.getName());
        else
        {
            names.add(annotation.value());
            for (String alternate : annotation.alternate()) names.add(alternate);
        }
        return names;
    }

    /** Gson's Excluder with GsonUtils' serialization exclusion strategy. */
    private static boolean excludeField(Field field, boolean serialize)
    {
        int modifiers = field.getModifiers();
        if ((modifiers & (Modifier.TRANSIENT | Modifier.STATIC)) != 0) return true;
        if (field.isSynthetic()) return true;
        if (isAnonymousOrNonStaticLocal(field.getType())) return true;
        return serialize && shouldSkipField(field);
    }

    /** {@code GsonUtils.GetGson()}'s ExclusionStrategy.shouldSkipField, unchanged. */
    private static boolean shouldSkipField(Field field)
    {
        Revision rev = GsonUtils.REVISION;
        boolean skip = false;

        GsonRevision revision = field.getAnnotation(GsonRevision.class);
        if (revision != null)
        {
            int head = rev.getVersion();
            int branchId = rev.getBranchID();

            if (revision.lbp3()) head = rev.getSubVersion();
            if (revision.alear())
            {
                branchId = rev.getCustomBranchID();
                head = rev.getCustomVersion();
            }

            if (revision.branch() != -1 && branchId != revision.branch())
                skip = true;
            if (revision.max() != -1 && head > revision.max())
                skip = true;
            if (revision.min() != -1 && head < revision.min())
                skip = true;
        }

        GsonRevisions revisions = field.getAnnotation(GsonRevisions.class);
        if (revisions != null)
        {
            boolean anyTrue = false;
            for (GsonRevision r : revisions.value())
            {
                int head = rev.getVersion();
                int branchId = rev.getBranchID();

                if (r.lbp3()) head = rev.getSubVersion();
                if (r.alear())
                {
                    branchId = rev.getCustomBranchID();
                    head = rev.getCustomVersion();
                }

                boolean max = ((r.max() == -1) || (r.max() >= head));
                boolean min = ((r.min() == -1) || (r.min() <= head));
                boolean branch = ((r.branch() == -1) || (r.branch() == branchId));

                if (max && min && branch)
                {
                    anyTrue = true;
                    break;
                }
            }
            skip = !anyTrue;
        }

        return skip;
    }

    private static boolean isAnonymousOrNonStaticLocal(Class<?> clazz)
    {
        if (Enum.class.isAssignableFrom(clazz)) return false;
        if (clazz.isArray() || clazz.isPrimitive()) return false;
        // Class.isAnonymousClass() isn't in TeaVM: anonymous classes have no simple name.
        String simpleName = clazz.getSimpleName();
        return clazz.isLocalClass() || simpleName == null || simpleName.isEmpty();
    }

    final class ReflectiveAdapter extends Adapter
    {
        final Class<?> raw;
        final LinkedHashMap<String, BoundField> fields;

        ReflectiveAdapter(Class<?> raw, LinkedHashMap<String, BoundField> fields)
        {
            this.raw = raw;
            this.fields = fields;
        }

        @Override boolean isReflective() { return true; }

        @Override
        JsonElement write(Object value)
        {
            if (value == null) return JsonNull.INSTANCE;
            JsonObject object = new JsonObject();
            for (BoundField field : fields.values())
                if (field.writeField(value))
                    object.add(field.name, field.write(value));
            return object;
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            if (!json.isJsonObject()) throw unexpected("BEGIN_OBJECT", json);
            Object instance = construct(raw);
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet())
            {
                BoundField field = fields.get(entry.getKey());
                if (field == null || !field.deserialized) continue;
                field.read(instance, entry.getValue());
            }
            return instance;
        }
    }

    /** Gson's ConstructorConstructor: no-args constructor, else a default implementation. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object construct(Class<?> raw)
    {
        // Common JDK types directly, since their constructors may not be reflectable.
        if (raw == ArrayList.class || raw == List.class || raw == Collection.class || raw == Iterable.class) return new ArrayList<>();
        if (raw == HashSet.class) return new HashSet<>();
        if (raw == LinkedHashSet.class || raw == Set.class) return new LinkedHashSet<>();
        if (raw == TreeSet.class || raw == SortedSet.class) return new TreeSet<>();
        if (raw == LinkedList.class) return new LinkedList<>();
        if (raw == ArrayDeque.class || raw == Queue.class) return new ArrayDeque<>();
        if (raw == HashMap.class) return new HashMap<>();
        if (raw == LinkedHashMap.class) return new LinkedHashMap<>();
        if (raw == TreeMap.class || raw == SortedMap.class) return new TreeMap<>();
        if (raw == Map.class) return new LinkedTreeMap<>();
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()))
            throw new RuntimeException("Unable to invoke no-args constructor for " + raw.getName()
                + ". Registering an InstanceCreator with Gson for this type may fix this problem.");
        try
        {
            Constructor<?> constructor = raw.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
        catch (NoSuchMethodException ex)
        {
            throw new RuntimeException("Unable to create " + raw.getName() + ": it has no no-args constructor", ex);
        }
        catch (Exception ex)
        {
            throw new RuntimeException("Failed to invoke " + raw.getName() + "() with no args", ex);
        }
    }

    // ---- collections, maps, arrays ---------------------------------------------------------

    final class CollectionAdapter extends Adapter
    {
        final Type type;
        final Class<?> raw;
        final Type elementType;
        final Adapter element;

        CollectionAdapter(Type type, Class<?> raw, Type elementType, Adapter element)
        {
            this.type = type;
            this.raw = raw;
            this.elementType = elementType;
            this.element = element;
        }

        @Override
        JsonElement write(Object value)
        {
            if (value == null) return JsonNull.INSTANCE;
            JsonArray array = new JsonArray();
            for (Object item : (Collection<?>) value) array.add(element.write(item));
            return array;
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            if (!json.isJsonArray()) throw unexpected("BEGIN_ARRAY", json);
            Collection<Object> collection;
            if (EnumSet.class.isAssignableFrom(raw))
            {
                if (!(elementType instanceof Class<?>))
                    throw new JsonIOException("Invalid EnumSet type: " + typeKey(type));
                collection = (Collection) EnumSet.noneOf((Class) elementType);
            }
            else collection = (Collection<Object>) construct(raw);
            for (JsonElement item : json.getAsJsonArray()) collection.add(element.read(item));
            return collection;
        }
    }

    final class MapAdapter extends Adapter
    {
        final Type type;
        final Class<?> raw;
        final Adapter key;
        final Adapter value;

        MapAdapter(Type type, Class<?> raw, Type keyType, Adapter key, Adapter value)
        {
            this.type = type;
            this.raw = raw;
            this.key = key;
            this.value = value;
        }

        @Override
        JsonElement write(Object map)
        {
            if (map == null) return JsonNull.INSTANCE;
            // complexMapKeySerialization
            boolean hasComplexKeys = false;
            List<JsonElement> keys = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet())
            {
                JsonElement keyElement = key.write(entry.getKey());
                keys.add(keyElement);
                values.add(entry.getValue());
                hasComplexKeys |= keyElement.isJsonArray() || keyElement.isJsonObject();
            }
            if (hasComplexKeys)
            {
                JsonArray array = new JsonArray();
                for (int i = 0; i < keys.size(); ++i)
                {
                    JsonArray pair = new JsonArray();
                    pair.add(keys.get(i));
                    pair.add(value.write(values.get(i)));
                    array.add(pair);
                }
                return array;
            }
            JsonObject object = new JsonObject();
            for (int i = 0; i < keys.size(); ++i)
                object.add(keyToString(keys.get(i)), value.write(values.get(i)));
            return object;
        }

        private String keyToString(JsonElement keyElement)
        {
            if (keyElement.isJsonPrimitive())
            {
                JsonPrimitive primitive = keyElement.getAsJsonPrimitive();
                if (primitive.isNumber()) return String.valueOf(primitive.getAsNumber());
                if (primitive.isBoolean()) return Boolean.toString(primitive.getAsBoolean());
                if (primitive.isString()) return primitive.getAsString();
                throw new AssertionError();
            }
            if (keyElement.isJsonNull()) return "null";
            throw new AssertionError();
        }

        @Override
        @SuppressWarnings("unchecked")
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            Map<Object, Object> map = (Map<Object, Object>) construct(raw);
            if (json.isJsonArray())
            {
                for (JsonElement entry : json.getAsJsonArray())
                {
                    if (!entry.isJsonArray()) throw unexpected("BEGIN_ARRAY", entry);
                    JsonArray pair = entry.getAsJsonArray();
                    if (pair.size() != 2) throw new JsonSyntaxException("Expected a [key, value] pair, got " + pair);
                    Object k = key.read(pair.get(0));
                    if (map.put(k, value.read(pair.get(1))) != null)
                        throw new JsonSyntaxException("duplicate key: " + k);
                }
            }
            else if (json.isJsonObject())
            {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet())
                {
                    Object k = key.read(new JsonPrimitive(entry.getKey()));
                    if (map.put(k, value.read(entry.getValue())) != null)
                        throw new JsonSyntaxException("duplicate key: " + k);
                }
            }
            else throw unexpected("BEGIN_OBJECT", json);
            return map;
        }
    }

    final class ArrayAdapter extends Adapter
    {
        final Class<?> componentRaw;
        final Adapter component;

        ArrayAdapter(Type componentType, Adapter component)
        {
            this.componentRaw = rawType(componentType);
            this.component = runtimeWrapper(component, componentType);
        }

        @Override
        JsonElement write(Object array)
        {
            if (array == null) return JsonNull.INSTANCE;
            JsonArray out = new JsonArray();
            for (int i = 0, n = Array.getLength(array); i < n; ++i)
                out.add(component.write(Array.get(array, i)));
            return out;
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            if (!json.isJsonArray()) throw unexpected("BEGIN_ARRAY", json);
            JsonArray in = json.getAsJsonArray();
            Object array = Array.newInstance(componentRaw, in.size());
            for (int i = 0; i < in.size(); ++i)
            {
                Object item = component.read(in.get(i));
                if (componentRaw.isPrimitive()) setPrimitive(array, i, item);
                else Array.set(array, i, item);
            }
            return array;
        }
    }

    private static void setPrimitive(Object array, int i, Object value)
    {
        if (value == null) throw new IllegalArgumentException("null in a primitive array");
        if (array instanceof int[]) ((int[]) array)[i] = ((Number) value).intValue();
        else if (array instanceof float[]) ((float[]) array)[i] = ((Number) value).floatValue();
        else if (array instanceof byte[]) ((byte[]) array)[i] = ((Number) value).byteValue();
        else if (array instanceof short[]) ((short[]) array)[i] = ((Number) value).shortValue();
        else if (array instanceof long[]) ((long[]) array)[i] = ((Number) value).longValue();
        else if (array instanceof double[]) ((double[]) array)[i] = ((Number) value).doubleValue();
        else if (array instanceof boolean[]) ((boolean[]) array)[i] = (Boolean) value;
        else if (array instanceof char[]) ((char[]) array)[i] = (Character) value;
        else throw new AssertionError();
    }

    // ---- custom serializers ----------------------------------------------------------------

    /** Gson's TreeTypeAdapter, for JsonSerializer/JsonDeserializer pairs. */
    final class TreeAdapter extends Adapter
    {
        final JsonSerializer<Object> serializer;
        final JsonDeserializer<Object> deserializer;
        final Type type;
        final Supplier delegateSupplier;
        Adapter delegate;

        @SuppressWarnings("unchecked")
        TreeAdapter(Object handler, Type type, Supplier delegateSupplier)
        {
            this.serializer = handler instanceof JsonSerializer ? (JsonSerializer<Object>) handler : null;
            this.deserializer = handler instanceof JsonDeserializer ? (JsonDeserializer<Object>) handler : null;
            this.type = type;
            this.delegateSupplier = delegateSupplier;
        }

        private Adapter delegate()
        {
            if (delegate == null) delegate = delegateSupplier.get();
            return delegate;
        }

        @Override
        JsonElement write(Object value)
        {
            if (serializer == null) return delegate().write(value);
            if (value == null) return JsonNull.INSTANCE;
            JsonElement tree = serializer.serialize(value, type, MiniGson.this);
            return tree == null ? JsonNull.INSTANCE : tree;
        }

        @Override
        Object read(JsonElement json)
        {
            if (deserializer == null) return delegate().read(json);
            if (json.isJsonNull()) return null;
            return deserializer.deserialize(json, type, MiniGson.this);
        }
    }

    // ---- enums, objects, JSON elements -----------------------------------------------------

    static final class EnumAdapter extends Adapter
    {
        final Map<String, Object> nameToConstant = new HashMap<>();
        final Map<Object, String> constantToName = new HashMap<>();

        EnumAdapter(Class<?> enumClass)
        {
            // Gson reads @SerializedName on the constants; cwlib's enums don't use it.
            for (Object constant : enumClass.getEnumConstants())
            {
                String name = ((Enum<?>) constant).name();
                nameToConstant.put(name, constant);
                constantToName.put(constant, name);
            }
        }

        @Override
        JsonElement write(Object value)
        {
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive(constantToName.get(value));
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            return nameToConstant.get(primitive(json, "a string").getAsString());
        }
    }

    final class ObjectAdapter extends Adapter
    {
        @Override
        JsonElement write(Object value)
        {
            if (value == null) return JsonNull.INSTANCE;
            Adapter adapter = getAdapter(value.getClass());
            if (adapter instanceof ObjectAdapter) return new JsonObject();
            return adapter.write(value);
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            if (json.isJsonArray())
            {
                List<Object> list = new ArrayList<>();
                for (JsonElement item : json.getAsJsonArray()) list.add(read(item));
                return list;
            }
            if (json.isJsonObject())
            {
                Map<String, Object> map = new LinkedTreeMap<>();
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet())
                    map.put(entry.getKey(), read(entry.getValue()));
                return map;
            }
            JsonPrimitive primitive = json.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsDouble();
            return primitive.getAsString();
        }
    }

    static final Adapter JSON_ELEMENT = new Adapter()
    {
        @Override JsonElement write(Object value) { return value == null ? JsonNull.INSTANCE : (JsonElement) value; }

        @Override Object read(JsonElement json) { return json; }
    };

    static final Adapter EXCLUDED = new Adapter()
    {
        @Override JsonElement write(Object value) { return JsonNull.INSTANCE; }

        @Override Object read(JsonElement json) { return null; }
    };

    static final Adapter CLASS = new Adapter()
    {
        @Override
        JsonElement write(Object value)
        {
            throw new UnsupportedOperationException("Attempted to serialize java.lang.Class: " + ((Class<?>) value).getName() + ". Forgot to register a type adapter?");
        }

        @Override
        Object read(JsonElement json)
        {
            throw new UnsupportedOperationException("Attempted to deserialize a java.lang.Class. Forgot to register a type adapter?");
        }
    };

    // ---- primitives (TypeAdapters) -----------------------------------------------------------

    private static JsonPrimitive primitive(JsonElement json, String expected)
    {
        if (!json.isJsonPrimitive()) throw unexpected(expected, json);
        return json.getAsJsonPrimitive();
    }

    /** JsonTreeReader.nextInt()/nextLong()/nextDouble(): numbers or strings. */
    private static JsonPrimitive number(JsonElement json, String expected)
    {
        JsonPrimitive p = primitive(json, expected);
        if (p.isBoolean()) throw unexpected(expected, json);
        return p;
    }

    private static Number numberValue(Object value) { return (Number) value; }

    private abstract static class NumberAdapter extends Adapter
    {
        @Override
        JsonElement write(Object value)
        {
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive(numberValue(value));
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            try { return parse(number(json, expected())); }
            catch (NumberFormatException ex) { throw new JsonSyntaxException(ex); }
        }

        abstract Object parse(JsonPrimitive p);

        abstract String expected();
    }

    static final Adapter INTEGER = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return p.getAsInt(); }

        @Override String expected() { return "an int"; }
    };

    static final Adapter SHORT = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return (short) p.getAsInt(); }

        @Override String expected() { return "an int"; }
    };

    static final Adapter BYTE = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return (byte) p.getAsInt(); }

        @Override String expected() { return "an int"; }
    };

    static final Adapter LONG = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return p.getAsLong(); }

        @Override String expected() { return "a long"; }
    };

    static final Adapter FLOAT = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return (float) p.getAsDouble(); }

        @Override String expected() { return "a double"; }
    };

    static final Adapter DOUBLE = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return p.getAsDouble(); }

        @Override String expected() { return "a double"; }
    };

    static final Adapter NUMBER = new NumberAdapter()
    {
        @Override Object parse(JsonPrimitive p) { return new LazilyParsedNumber(p.getAsString()); }

        @Override String expected() { return "a number"; }
    };

    static final Adapter BOOLEAN = new Adapter()
    {
        @Override
        JsonElement write(Object value)
        {
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive((Boolean) value);
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            JsonPrimitive p = primitive(json, "a boolean");
            if (p.isString()) return Boolean.parseBoolean(p.getAsString());
            if (!p.isBoolean()) throw unexpected("a boolean", json);
            return p.getAsBoolean();
        }
    };

    static final Adapter BOOLEAN_AS_STRING = new Adapter()
    {
        @Override
        JsonElement write(Object value)
        {
            return new JsonPrimitive(value == null ? "null" : value.toString());
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            return Boolean.valueOf(primitive(json, "a string").getAsString());
        }
    };

    static final Adapter CHARACTER = new Adapter()
    {
        @Override
        JsonElement write(Object value)
        {
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(value));
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            String text = number(json, "a string").getAsString();
            if (text.length() != 1) throw new JsonSyntaxException("Expecting character, got: " + text);
            return text.charAt(0);
        }
    };

    static final Adapter STRING = new Adapter()
    {
        @Override
        JsonElement write(Object value)
        {
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive((String) value);
        }

        @Override
        Object read(JsonElement json)
        {
            if (json.isJsonNull()) return null;
            JsonPrimitive p = primitive(json, "a string");
            if (p.isBoolean()) return Boolean.toString(p.getAsBoolean());
            return p.getAsString();
        }
    };

    private static Adapter builtin(Class<?> raw)
    {
        if (raw == String.class) return STRING;
        if (raw == int.class || raw == Integer.class) return INTEGER;
        if (raw == boolean.class || raw == Boolean.class) return BOOLEAN;
        if (raw == byte.class || raw == Byte.class) return BYTE;
        if (raw == short.class || raw == Short.class) return SHORT;
        if (raw == long.class || raw == Long.class) return LONG;
        if (raw == double.class || raw == Double.class) return DOUBLE;
        if (raw == float.class || raw == Float.class) return FLOAT;
        if (raw == Number.class) return NUMBER;
        if (raw == char.class || raw == Character.class) return CHARACTER;
        if (raw.getName().startsWith("java.") && !raw.isArray() && !Collection.class.isAssignableFrom(raw)
            && !Map.class.isAssignableFrom(raw) && raw != Class.class && raw != Object.class && !raw.isEnum())
            throw new UnsupportedOperationException("No JSON adapter for " + raw.getName());
        return null;
    }

    // ---- types ----------------------------------------------------------------------------------

    private static RuntimeException unexpected(String expected, JsonElement json)
    {
        String actual = json.isJsonObject() ? "BEGIN_OBJECT" : json.isJsonArray() ? "BEGIN_ARRAY"
            : json.isJsonNull() ? "NULL" : json.getAsJsonPrimitive().isBoolean() ? "BOOLEAN"
            : json.getAsJsonPrimitive().isNumber() ? "NUMBER" : "STRING";
        return new JsonSyntaxException(new IllegalStateException("Expected " + expected + " but was " + actual));
    }

    private static Type resolve(Type type)
    {
        // cwlib's JSON classes aren't generic, so a type variable can only be
        // resolved to its bound (what Gson ends up doing for raw uses too).
        return type;
    }

    static Class<?> rawType(Type type)
    {
        if (type instanceof Class<?>) return (Class<?>) type;
        if (type instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) type).getRawType();
        if (type instanceof GenericArrayType)
            return Array.newInstance(rawType(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        if (type instanceof WildcardType) return rawType(((WildcardType) type).getUpperBounds()[0]);
        if (type instanceof TypeVariable<?>)
        {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : rawType(bounds[0]);
        }
        throw new IllegalArgumentException("Unsupported type " + type);
    }

    /** Argument {@code index} of a parameterized collection or map type, or Object. */
    private static Type typeArgument(Type type, int index, int count)
    {
        if (type instanceof ParameterizedType)
        {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length == count)
            {
                Type arg = args[index];
                if (arg instanceof WildcardType) return ((WildcardType) arg).getUpperBounds()[0];
                return arg;
            }
        }
        return Object.class;
    }

    /**
     * Gson's TypeToken equality, without class names (which a minified TeaVM
     * build may not keep).
     */
    static final class TypeKey
    {
        final Type type;
        final int hash;

        TypeKey(Type type)
        {
            this.type = type;
            this.hash = hash(type);
        }

        private static int hash(Type t)
        {
            if (t instanceof Class<?>) return t.hashCode();
            if (t instanceof ParameterizedType)
            {
                int h = hash(((ParameterizedType) t).getRawType());
                for (Type arg : ((ParameterizedType) t).getActualTypeArguments()) h = h * 31 + hash(arg);
                return h;
            }
            if (t instanceof GenericArrayType) return 17 + hash(((GenericArrayType) t).getGenericComponentType());
            if (t instanceof WildcardType) return 23 + hash(((WildcardType) t).getUpperBounds()[0]);
            return 0;
        }

        static boolean same(Type a, Type b)
        {
            if (a == b) return true;
            if (a instanceof ParameterizedType && b instanceof ParameterizedType)
            {
                ParameterizedType pa = (ParameterizedType) a, pb = (ParameterizedType) b;
                Type[] x = pa.getActualTypeArguments(), y = pb.getActualTypeArguments();
                if (!same(pa.getRawType(), pb.getRawType()) || x.length != y.length) return false;
                for (int i = 0; i < x.length; ++i) if (!same(x[i], y[i])) return false;
                return true;
            }
            if (a instanceof GenericArrayType && b instanceof GenericArrayType)
                return same(((GenericArrayType) a).getGenericComponentType(), ((GenericArrayType) b).getGenericComponentType());
            if (a instanceof WildcardType && b instanceof WildcardType)
            {
                WildcardType wa = (WildcardType) a, wb = (WildcardType) b;
                return same(wa.getUpperBounds()[0], wb.getUpperBounds()[0])
                    && wa.getLowerBounds().length == wb.getLowerBounds().length;
            }
            if (a instanceof TypeVariable<?> && b instanceof TypeVariable<?>)
                return ((TypeVariable<?>) a).getName().equals(((TypeVariable<?>) b).getName())
                    && ((TypeVariable<?>) a).getGenericDeclaration() == ((TypeVariable<?>) b).getGenericDeclaration();
            return false;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) { return o instanceof TypeKey && same(type, ((TypeKey) o).type); }
    }

    static String typeKey(Type type)
    {
        if (type instanceof Class<?>) return ((Class<?>) type).getName();
        if (type instanceof ParameterizedType)
        {
            ParameterizedType p = (ParameterizedType) type;
            StringBuilder key = new StringBuilder(typeKey(p.getRawType())).append('<');
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; ++i)
                key.append(i == 0 ? "" : ",").append(typeKey(args[i]));
            return key.append('>').toString();
        }
        if (type instanceof GenericArrayType)
            return typeKey(((GenericArrayType) type).getGenericComponentType()) + "[]";
        if (type instanceof WildcardType)
            return "? extends " + typeKey(((WildcardType) type).getUpperBounds()[0]);
        if (type instanceof TypeVariable<?>) return "var " + ((TypeVariable<?>) type).getName();
        return String.valueOf(type);
    }
}
