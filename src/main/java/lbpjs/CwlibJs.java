package lbpjs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import cwlib.enums.ResourceType;
import cwlib.enums.SerializationType;
import cwlib.io.Resource;
import cwlib.types.SerializedResource;
import cwlib.types.data.WrappedResource;
import lbpjs.json.MiniGson;
import org.teavm.jso.JSExport;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * What the compiled cwlib.js exports. Resource files cross the boundary as
 * binary strings (one char per byte, 0-255).
 * <p>
 * The JSON is the toolkit's "Export > JSON" / "New > JSON" format: a
 * {@link WrappedResource} with the revision, type and the whole resource.
 */
public final class CwlibJs
{
    static
    {
        // JOML: plain number formatting (TeaVM's DecimalFormat rejects JOML's
        // " 0.000E0;-" pattern), NIO instead of sun.misc.Unsafe, no Math.fma.
        System.setProperty("joml.format", "false");
        System.setProperty("joml.nounsafe", "true");
        System.setProperty("joml.useMathFma", "false");
    }

    private CwlibJs() { }

    public static void main(String[] args) { }

    /**
     * Resource file to JSON text for JSON.parse: like the toolkit's export, but
     * NaN/Infinity and integers JavaScript can't hold exactly are written as
     * strings (which Gson and this library read back as numbers).
     */
    @JSExport
    public static String resourceToJson(String binary)
    {
        try { return toJsText(toTree(bytes(binary))); }
        catch (Throwable ex) { throw describe(ex); }
    }

    /** Resource file to JSON text exactly as the toolkit writes it (Gson, pretty printed). */
    @JSExport
    public static String resourceToGsonJson(String binary)
    {
        try { return toGsonText(toTree(bytes(binary))); }
        catch (Throwable ex) { throw describe(ex); }
    }

    /** JSON text (from either function above, or the toolkit) to a resource file. */
    @JSExport
    public static String jsonToResource(String json)
    {
        try { return binary(fromJson(json)); }
        catch (Throwable ex) { throw describe(ex); }
    }

    /** An exception whose message says what went wrong, for the JavaScript side. */
    private static RuntimeException describe(Throwable ex)
    {
        StringBuilder message = new StringBuilder();
        for (Throwable t = ex; t != null && message.length() < 600; t = t.getCause())
        {
            if (message.length() != 0) message.append(", caused by ");
            String name = t.getClass().getSimpleName();
            message.append(name == null || name.isEmpty() ? "Error" : name);
            if (t.getMessage() != null) message.append(": ").append(t.getMessage());
            if (t.getCause() == t) break;
        }
        return new RuntimeException(message.toString(), ex);
    }

    // ---- shared with the JVM tests ---------------------------------------------------------

    public static JsonElement toTree(byte[] file)
    {
        SerializedResource resource = new SerializedResource(file);
        ResourceType type = resource.getResourceType();
        SerializationType method = resource.getSerializationType();
        if (method != SerializationType.BINARY && method != SerializationType.ENCRYPTED_BINARY)
            throw new IllegalArgumentException("Only binary resources can be converted to JSON, this one is " + method);
        if (type.getCompressable() == null)
            throw new IllegalArgumentException("cwlib can't convert " + type + " resources to JSON");
        WrappedResource wrapper = new WrappedResource(resource);
        return MiniGson.toTree(wrapper, resource.getRevision());
    }

    public static byte[] fromJson(String json)
    {
        JsonElement tree = JsonParser.parseString(json);
        if (!tree.isJsonObject())
            throw new IllegalArgumentException("Expected a JSON object with \"revision\", \"type\" and \"resource\"");
        com.google.gson.JsonObject object = tree.getAsJsonObject();
        for (String key : new String[] { "revision", "type", "resource" })
            if (!object.has(key) || object.get(key).isJsonNull())
                throw new IllegalArgumentException("JSON needs \"revision\", \"type\" and \"resource\" (missing \"" + key + "\")");
        if (!object.get("type").isJsonPrimitive())
            throw new IllegalArgumentException("\"type\" must be a resource type name such as \"PLAN\"");
        ResourceType named = null;
        for (ResourceType t : ResourceType.values())
            if (t.name().equals(object.get("type").getAsString())) named = t;
        if (named == null)
            throw new IllegalArgumentException("Unknown resource type \"" + object.get("type").getAsString() + "\"");
        if (named.getCompressable() == null)
            throw new IllegalArgumentException("cwlib can't build " + named + " resources from JSON");
        WrappedResource wrapper = MiniGson.fromTree(tree, WrappedResource.class);
        if (wrapper == null || wrapper.type == null || wrapper.resource == null)
            throw new IllegalArgumentException("JSON needs \"revision\", \"type\" and \"resource\"");
        if (!(wrapper.resource instanceof Resource))
            throw new IllegalArgumentException(wrapper.type + " can't be built from JSON");
        return wrapper.build();
    }

    /** Gson.toJson(...) with GsonUtils' settings: pretty printing, nulls, special floats. */
    public static String toGsonText(JsonElement tree)
    {
        try
        {
            StringWriter out = new StringWriter();
            JsonWriter writer = new JsonWriter(out);
            writer.setIndent("  ");
            writer.setSerializeNulls(true);
            writer.setLenient(true);
            writer.setHtmlSafe(true);
            com.google.gson.internal.Streams.write(tree, writer);
            return out.toString();
        }
        catch (IOException ex) { throw new RuntimeException(ex); }
    }

    private static final double MAX_SAFE_INTEGER = 9007199254740991.0;

    /** Compact JSON that JSON.parse accepts without losing values. */
    public static String toJsText(JsonElement tree)
    {
        StringBuilder out = new StringBuilder();
        writeJs(tree, out);
        return out.toString();
    }

    private static void writeJs(JsonElement e, StringBuilder out)
    {
        if (e == null || e.isJsonNull()) out.append("null");
        else if (e.isJsonObject())
        {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet())
            {
                if (!first) out.append(',');
                first = false;
                quote(entry.getKey(), out);
                out.append(':');
                writeJs(entry.getValue(), out);
            }
            out.append('}');
        }
        else if (e.isJsonArray())
        {
            out.append('[');
            for (int i = 0; i < e.getAsJsonArray().size(); ++i)
            {
                if (i != 0) out.append(',');
                writeJs(e.getAsJsonArray().get(i), out);
            }
            out.append(']');
        }
        else
        {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) out.append(p.getAsBoolean());
            else if (p.isString()) quote(p.getAsString(), out);
            else
            {
                Number n = p.getAsNumber();
                String text = n.toString();
                if (n instanceof Float || n instanceof Double)
                {
                    double d = n.doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) quote(text, out);
                    else out.append(text);
                }
                else if (n instanceof Long && Math.abs((double) n.longValue()) > MAX_SAFE_INTEGER) quote(text, out);
                else out.append(text);
            }
        }
    }

    private static void quote(String s, StringBuilder out)
    {
        out.append('"');
        for (int i = 0; i < s.length(); ++i)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || (c >= 0xd800 && c <= 0xdfff) || c == 0x2028 || c == 0x2029)
                    {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; ++k) out.append('0');
                        out.append(hex);
                    }
                    else out.append(c);
            }
        }
        out.append('"');
    }

    // ---- binary strings ---------------------------------------------------------------------------

    public static byte[] bytes(String binary)
    {
        if (binary == null) throw new IllegalArgumentException("No resource data");
        byte[] out = new byte[binary.length()];
        for (int i = 0; i < out.length; ++i)
        {
            char c = binary.charAt(i);
            if (c > 0xff)
                throw new IllegalArgumentException("Resource data must be a binary string (one char per byte), found U+"
                    + Integer.toHexString(c) + " at " + i);
            out[i] = (byte) c;
        }
        return out;
    }

    public static String binary(byte[] bytes)
    {
        char[] out = new char[bytes.length];
        for (int i = 0; i < out.length; ++i) out[i] = (char) (bytes[i] & 0xff);
        return new String(out);
    }
}
