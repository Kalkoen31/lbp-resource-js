package lbpjs;

import cwlib.enums.CompressionFlags;
import cwlib.enums.ResourceType;
import cwlib.io.Resource;
import cwlib.types.SerializedResource;
import cwlib.types.data.Revision;
import cwlib.types.data.WrappedResource;
import cwlib.util.GsonUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the Gson replacement against real Gson on the JVM: for made-up
 * resources of every type cwlib can turn into JSON, at several game revisions,
 * toolkit JSON (GsonUtils.toJSON) and ours must be the same text, and building
 * a resource back from that JSON must give the same bytes.
 * <p>
 * Also writes the cases to target/fixtures for the JavaScript tests, which
 * check that the TeaVM build gives the same results as the JVM.
 */
public class ParityTest
{
    static final Map<String, Revision> REVISIONS = new LinkedHashMap<>();
    static
    {
        REVISIONS.put("lbp1", new Revision(0x272));
        REVISIONS.put("leerdammer", new Revision(0x272, 0x4c44, 0x17));
        REVISIONS.put("lbp2", new Revision(0x3f8));
        REVISIONS.put("vita", new Revision(0x3e2, 0x4431, 0x87));
        REVISIONS.put("lbp3", new Revision(0x021803f8));
    }

    static final long[] SEEDS = { 0, 1, 2, 3 };

    record Outcome(String text, String error) { }

    static Outcome run(java.util.concurrent.Callable<String> call)
    {
        try { return new Outcome(call.call(), null); }
        catch (Throwable ex) { return new Outcome(null, ex.getClass().getSimpleName()); }
    }

    static final File out = new File(System.getProperty("fixtures.dir", "target/fixtures"));
    static final List<String> index = new ArrayList<>();
    static final List<String> report = new ArrayList<>();
    static int compared = 0, built = 0;
    static final Set<String> typesCovered = new TreeSet<>();

    @Test
    public void matchesGson() throws Exception
    {
        out.mkdirs();

        for (ResourceType type : ResourceType.values())
        {
            Class<? extends Resource> clazz = type.getCompressable();
            if (clazz == null) continue;
            for (Map.Entry<String, Revision> rev : REVISIONS.entrySet())
            {
                Revision revision = rev.getValue();
                byte flags = revision.getDefaultCompressionFlags();
                Set<String> seenFiles = new HashSet<>();
                for (long seed : SEEDS)
                {
                    byte[] file = makeFile(clazz, revision, flags, seed);
                    if (file == null) continue;
                    String key = Base64.getEncoder().encodeToString(file);
                    if (!seenFiles.add(key)) continue;
                    check(type.name().toLowerCase() + "-" + rev.getKey() + "-" + seed, type.name(), file, revision);
                }
            }
        }
        for (Map.Entry<String, Revision> rev : REVISIONS.entrySet())
        {
            Revision revision = rev.getValue();
            byte flags = revision.getDefaultCompressionFlags();
            for (long seed = 1; seed <= 3; ++seed)
            {
                byte[] plan = SpecialResources.plan(revision, flags, seed * 7 + rev.getKey().hashCode());
                if (plan != null) check("things-plan-" + rev.getKey() + "-" + seed, "PLAN", plan, revision);
                byte[] level = SpecialResources.level(revision, flags, seed * 11 + rev.getKey().hashCode());
                if (level != null) check("things-level-" + rev.getKey() + "-" + seed, "LEVEL", level, revision);
                byte[] mesh = SpecialResources.mesh(revision, flags, seed);
                if (mesh != null) check("mesh-" + rev.getKey() + "-" + seed, "MESH", mesh, revision);
            }
        }
        Files.writeString(new File(out, "index.json").toPath(), "[\n" + String.join(",\n", index) + "\n]\n");
        Files.writeString(new File(out, "report.txt").toPath(), "compared " + compared + ", rebuilt " + built
            + "\ntypes: " + typesCovered + "\n" + String.join("\n", report) + "\n");
        System.out.println("[parity] compared " + compared + " JSON exports and " + built + " rebuilds over " + typesCovered.size() + " types");
        for (String line : report) System.out.println("[parity] " + line);
        assertTrue(compared > 50, "too few cases: " + compared);
    }


    static void check(String name, String typeName, byte[] file, Revision revision) throws Exception
    {

        Outcome gson = run(() -> GsonUtils.toJSON(new WrappedResource(new SerializedResource(file)), revision));
        Outcome mini = run(() -> CwlibJs.toGsonText(CwlibJs.toTree(file)));
        if (gson.error() != null)
        {
            // Gson can't export it either: ours has to fail too.
            assertNotNull(mini.error(), name + ": Gson failed (" + gson.error() + ") but we didn't");
            report.add(name + ": export fails in both (" + gson.error() + " / " + mini.error() + ")");
            return;
        }
        assertNull(mini.error(), name + ": Gson worked but we failed with " + mini.error());
        assertEquals(gson.text(), mini.text(), name + ": JSON differs");
        compared++;
        typesCovered.add(typeName);

        Outcome gsonBuilt = run(() -> latin1(GsonUtils.fromJSON(gson.text(), WrappedResource.class).build()));
        Outcome miniBuilt = run(() -> latin1(CwlibJs.fromJson(gson.text())));
        String jsText = CwlibJs.toJsText(CwlibJs.toTree(file));
        Outcome miniFromJs = run(() -> latin1(CwlibJs.fromJson(jsText)));
        if (gsonBuilt.error() != null)
        {
            assertNotNull(miniBuilt.error(), name + ": Gson build failed (" + gsonBuilt.error() + ") but ours worked");
            report.add(name + ": building from JSON fails in both (" + gsonBuilt.error() + " / " + miniBuilt.error() + ")");
        }
        else
        {
            assertNull(miniBuilt.error(), name + ": Gson built it but we failed with " + miniBuilt.error());
            assertEquals(gsonBuilt.text(), miniBuilt.text(), name + ": rebuilt bytes differ");
            assertEquals(miniBuilt.text(), miniFromJs.text(), name + ": rebuilding from the JS-friendly JSON differs");
            built++;
        }

        Files.write(new File(out, name + ".bin").toPath(), file);
        Files.writeString(new File(out, name + ".gson.json").toPath(), gson.text());
        if (gsonBuilt.error() == null)
            Files.write(new File(out, name + ".rebuilt.bin").toPath(), gsonBuilt.text().getBytes(StandardCharsets.ISO_8859_1));
        index.add("{\"name\":\"" + name + "\",\"type\":\"" + typeName + "\",\"rebuilds\":" + (gsonBuilt.error() == null) + "}");
    }

    static String latin1(byte[] bytes) { return new String(bytes, StandardCharsets.ISO_8859_1); }

    /** A made-up resource that survives a binary round trip, or null. */
    static byte[] makeFile(Class<? extends Resource> clazz, Revision revision, byte flags, long seed)
    {
        double[] chances = seed == 0 ? new double[] { 0 } : new double[] { 0.6, 0.3, 0.1 };
        for (double chance : chances)
        {
            try
            {
                Constructor<? extends Resource> constructor = clazz.getDeclaredConstructor();
                constructor.setAccessible(true);
                Resource resource = constructor.newInstance();
                if (chance > 0) new ResourceFiller(seed * 31 + clazz.getName().hashCode(), 4, chance).fill(resource, 0);
                byte[] file = SerializedResource.compress(resource, revision, flags);
                // Must read back, and write again to the same bytes.
                Resource back = new SerializedResource(file).loadResource(clazz);
                byte[] again = SerializedResource.compress(back, revision, flags);
                if (Arrays.equals(file, again)) return file;
            }
            catch (Throwable ignored) { }
        }
        return null;
    }
}
