package lbpjs;

// Writes made-up LBP resources (no game data) with cwlib's SerializedResource,
// plus what cwlib reads back from them, for the decompress_resource /
// compress_resource tests in test/lbp-resource.test.mjs:
//
//   <fixtures>/containers/containers.json  expected header info per case
//   <fixtures>/containers/<case>.bin       resource file written by cwlib
//   <fixtures>/containers/<case>.data      decompressed payload, as cwlib reads it
import cwlib.enums.CompressionFlags;
import cwlib.enums.ResourceType;
import cwlib.enums.SerializationType;
import cwlib.io.serializer.SerializationData;
import cwlib.types.SerializedResource;
import cwlib.types.data.GUID;
import cwlib.types.data.ResourceDescriptor;
import cwlib.types.data.Revision;
import cwlib.types.data.SHA1;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ContainerFixturesTest
{
    File out;
    List<String> cases = new ArrayList<>();

    // Compressible but not trivial: words from a small vocabulary.
    byte[] payload(int size, long seed)
    {
        Random random = new Random(seed);
        String[] words = { "sackboy ", "craftworld ", "\u0018U\u0005\u0001", "popit ", "\0\0\0\0", "level ", "ÿþ" };
        StringBuilder text = new StringBuilder();
        while (text.length() < size) text.append(words[random.nextInt(words.length)]);
        return text.substring(0, size).getBytes(StandardCharsets.ISO_8859_1);
    }

    ResourceDescriptor[] deps()
    {
        return new ResourceDescriptor[] {
            new ResourceDescriptor(new GUID(12), ResourceType.MESH),
            new ResourceDescriptor(new SHA1("5ad8be5e446ddedb0c90b247f1c01a605cc2f2b0"), ResourceType.SKELETON_MAP),
            new ResourceDescriptor(new GUID(0xfffffffeL), new SHA1("0123456789abcdef0123456789abcdef01234567"), ResourceType.PLAN),
        };
    }

    String q(String s) { return s == null ? "null" : "\"" + s + "\""; }

    void binary(String name, ResourceType type, Revision revision, byte flags,
                       boolean compressed, byte[] data, ResourceDescriptor[] dependencies,
                       byte[] extra, byte extraFlags) throws Exception
    {
        SerializationData sd = new SerializationData(data, extra, revision, flags, extraFlags,
            type, SerializationType.BINARY, dependencies);
        byte[] file = SerializedResource.compress(sd, compressed);
        write(name, file);
    }

    void write(String name, byte[] file) throws Exception
    {
        Files.write(new File(out, name + ".bin").toPath(), file);
        SerializedResource read = new SerializedResource(file);
        Files.write(new File(out, name + ".data").toPath(), read.getStream().getBuffer());

        StringBuilder json = new StringBuilder("{\"name\":" + q(name) + ",\"resource_type\":" + q(read.getResourceType().name()));
        if (read.getSerializationType() == SerializationType.BINARY)
        {
            Revision r = read.getRevision();
            int f = read.getCompressionFlags();
            json.append(",\"revision\":").append(Integer.toUnsignedString(r.getHead()))
                .append(",\"branch\":[").append(r.getBranchID() & 0xffff).append(",").append(r.getBranchRevision() & 0xffff).append("]")
                .append(",\"compression_flag_integers\":").append((f & CompressionFlags.USE_COMPRESSED_INTEGERS) != 0)
                .append(",\"compression_flag_matricies\":").append((f & CompressionFlags.USE_COMPRESSED_MATRICES) != 0)
                .append(",\"compression_flag_vectors\":").append((f & CompressionFlags.USE_COMPRESSED_VECTORS) != 0)
                .append(",\"dependencies\":[");
            ResourceDescriptor[] deps = read.getDependencies();
            for (int i = 0; i < deps.length; ++i)
            {
                ResourceDescriptor d = deps[i];
                json.append(i == 0 ? "" : ",").append("{\"resource_type\":").append(q(d.getType().name()))
                    .append(",\"hash\":").append(q(d.isHash() ? d.getSHA1().toString() : null))
                    .append(",\"guid\":").append(d.isGUID() ? Long.toString(d.getGUID().getValue()) : "null").append("}");
            }
            json.append("]");
            if (r.hasExtraData())
                json.append(",\"alear_branch_id\":").append(Integer.toUnsignedString(r.getCustomBranchID()))
                    .append(",\"alear_branch_revision\":").append(r.getCustomVersion());
        }
        cases.add(json.append("}").toString());
    }

    @Test
    public void writeFixtures() throws Exception
    {
        out = new File(System.getProperty("fixtures.dir", "target/fixtures"), "containers");
        out.mkdirs();
        byte all = CompressionFlags.USE_ALL_COMPRESSION, none = CompressionFlags.USE_NO_COMPRESSION;

        // LBP2 (0x3e2): compression flags in the header.
        binary("lbp2-plan", ResourceType.PLAN, new Revision(0x3e2), all, true, payload(5000, 1), deps(), null, none);
        binary("lbp2-uncompressed", ResourceType.PLAN, new Revision(0x3e2), all, false, payload(300, 2), deps(), null, none);
        binary("lbp2-multichunk", ResourceType.LEVEL, new Revision(0x3e2), (byte) 3, true, payload(0x8000 * 3 + 123, 3), deps(), null, none);
        binary("lbp2-exact-chunk", ResourceType.MESH, new Revision(0x3e2), all, true, payload(0x8000, 4), new ResourceDescriptor[0], null, none);
        binary("lbp2-empty", ResourceType.MATERIAL, new Revision(0x3e2), all, true, new byte[0], new ResourceDescriptor[0], null, none);
        byte[] noise = new byte[4000];
        new Random(5).nextBytes(noise);
        binary("lbp2-incompressible", ResourceType.PLAN, new Revision(0x3e2), all, true, noise, deps(), null, none);
        // LBP3: revision with a sub-version and branch in the header.
        binary("lbp3-branch", ResourceType.GFX_MATERIAL, new Revision(0x021803f9, 3, 4), all, true, payload(2000, 6), deps(), null, none);
        // LBP1 final revision: branch fields but no compression flags.
        binary("lbp1-final", ResourceType.PLAN, new Revision(0x272), none, true, payload(1500, 7), deps(), null, none);
        // Leerdammer branch: compression flags from branch revision 2.
        binary("leerdammer", ResourceType.PLAN, new Revision(0x272, 0x4c44, 0x8), (byte) 5, true, payload(1500, 8), deps(), null, none);
        // Before the "is compressed" header byte.
        binary("pre-0x189", ResourceType.PLAN, new Revision(0x188), none, true, payload(1000, 9), deps(), null, none);
        binary("pre-0x189-uncompressed", ResourceType.PLAN, new Revision(0x188), none, false, payload(200, 10), deps(), null, none);
        // Alear (custom server) extra data after the dependency table.
        Revision alear = new Revision(0x3e2);
        alear.setCustomBranchDescription(0x414c5352, 3);
        binary("alear-extra", ResourceType.PLAN, alear, all, true, payload(800, 11), deps(), payload(400, 12), (byte) 1);
        // Text resources keep everything after the 4 byte magic.
        write("text", SerializedResource.compress(new SerializationData(
            "some text resource\nline 2\n".getBytes(StandardCharsets.ISO_8859_1), null, null, none, none,
            ResourceType.GAME_CONSTANTS, SerializationType.TEXT, null)));

        Files.writeString(new File(out, "containers.json").toPath(), "[\n" + String.join(",\n", cases) + "\n]\n");
        System.out.println("[containers] wrote " + cases.size() + " container fixtures to " + out);
    }
}
