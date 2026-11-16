package lbpjs;

import cwlib.enums.Part;
import cwlib.io.Resource;
import cwlib.resources.RLevel;
import cwlib.resources.RMesh;
import cwlib.resources.RPlan;
import cwlib.structs.mesh.Bone;
import cwlib.structs.things.Thing;
import cwlib.structs.things.parts.PMetadata;
import cwlib.structs.things.parts.PWorld;
import cwlib.types.SerializedResource;
import cwlib.types.data.Revision;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;

/**
 * Made-up resources that need more than default values: plans and levels with
 * things carrying random parts, and small meshes.
 */
final class SpecialResources
{
    /** Bytes of a resource that reads back and writes again identically, or null. */
    static byte[] stable(Resource resource, Class<? extends Resource> clazz, Revision revision, byte flags)
    {
        try
        {
            byte[] file = SerializedResource.compress(resource, revision, flags);
            Resource back = new SerializedResource(file).loadResource(clazz);
            byte[] again = SerializedResource.compress(back, revision, flags);
            return Arrays.equals(file, again) ? file : null;
        }
        catch (Throwable ex) { return null; }
    }

    static byte[] attempt(Supplier<Resource> build, Class<? extends Resource> clazz, Revision revision, byte flags)
    {
        try { return stable(build.get(), clazz, revision, flags); }
        catch (Throwable ex) { return null; }
    }

    static Object newPart(Part part, ResourceFiller filler)
    {
        try
        {
            Constructor<?> constructor = part.getSerializable().getDeclaredConstructor();
            constructor.setAccessible(true);
            Object value = constructor.newInstance();
            filler.fill(value, 2);
            return value;
        }
        catch (Throwable ex) { return null; }
    }

    /**
     * Adds random parts to the things one at a time, keeping each one only if
     * the resource still survives a binary round trip.
     */
    static byte[] withParts(List<Thing> things, Supplier<Resource> build, Class<? extends Resource> clazz,
                            Revision revision, byte flags, long seed)
    {
        Random random = new Random(seed);
        byte[] file = attempt(build, clazz, revision, flags);
        if (file == null) return null;
        Part[] parts = Part.values();
        for (Thing thing : things)
        {
            for (int attempt = 0; attempt < 6; ++attempt)
            {
                Part part = parts[random.nextInt(parts.length)];
                if (part == Part.WORLD || thing.hasPart(part)) continue;
                Object value = newPart(part, new ResourceFiller(random.nextLong(), 3, 0.5));
                if (value == null) continue;
                thing.setPart(part, (cwlib.io.Serializable) value);
                byte[] next = attempt(build, clazz, revision, flags);
                if (next == null)
                {
                    thing.setPart(part, null);
                    // also try the default part
                    Object plain = newPart(part, new ResourceFiller(0, 0, 0));
                    if (plain == null) continue;
                    thing.setPart(part, (cwlib.io.Serializable) plain);
                    next = attempt(build, clazz, revision, flags);
                    if (next == null) { thing.setPart(part, null); continue; }
                }
                file = next;
            }
        }
        return file;
    }

    static List<Thing> things(int count, int firstUid, Thing world)
    {
        List<Thing> things = new ArrayList<>();
        for (int i = 0; i < count; ++i)
        {
            Thing thing = new Thing(firstUid + i);
            if (i > 0 && i % 2 == 0) thing.parent = things.get(i - 1);
            if (i > 1) thing.groupHead = things.get(1);
            thing.world = world;
            things.add(thing);
        }
        return things;
    }

    static byte[] plan(Revision revision, byte flags, long seed)
    {
        List<Thing> things = things(4, 10, null);
        PMetadata metadata = new PMetadata();
        metadata.titleKey = 1234;
        return withParts(things, () -> new RPlan(revision, flags, things.toArray(Thing[]::new), metadata),
            RPlan.class, revision, flags, seed);
    }

    static byte[] level(Revision revision, byte flags, long seed)
    {
        RLevel level = new RLevel();
        PWorld world = level.worldThing.getPart(Part.WORLD);
        List<Thing> things = things(4, 100, level.worldThing);
        world.things.addAll(things);
        return withParts(things, () -> level, RLevel.class, revision, flags, seed);
    }

    static byte[] mesh(Revision revision, byte flags, long seed)
    {
        Random random = new Random(seed);
        int verts = 3 + random.nextInt(3);
        byte[][] streams = new byte[2 + random.nextInt(2)][verts * 0x10];
        for (byte[] stream : streams) random.nextBytes(stream);
        byte[] attributes = new byte[verts * 8];
        random.nextBytes(attributes);
        byte[] indices = new byte[6];
        for (int i = 0; i < 3; ++i) indices[i * 2 + 1] = (byte) i;
        Bone bone = new Bone("bone_" + seed);
        RMesh mesh = new RMesh(streams, attributes, indices, new Bone[] { bone });
        return stable(mesh, RMesh.class, revision, flags);
    }
}
