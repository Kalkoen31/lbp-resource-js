package lbpjs.json;

import cwlib.io.gson.*;
import cwlib.resources.RGfxMaterial;
import cwlib.resources.RMaterial;
import cwlib.resources.RTranslationTable;
import cwlib.structs.inventory.CreationHistory;
import cwlib.structs.mesh.Bone;
import cwlib.structs.slot.SlotID;
import cwlib.structs.things.Thing;
import cwlib.structs.things.components.poppet.PoppetMaterialOverride;
import cwlib.structs.things.components.script.ScriptInstance;
import cwlib.structs.things.components.script.ScriptObjectUID;
import cwlib.structs.things.parts.PPos;
import cwlib.structs.things.parts.PScriptName;
import cwlib.structs.things.parts.PShape;
import cwlib.types.data.GUID;
import cwlib.types.data.NetworkOnlineID;
import cwlib.types.data.NetworkPlayerID;
import cwlib.types.data.ResourceDescriptor;
import cwlib.types.data.SHA1;
import cwlib.types.data.WrappedResource;
import cwlib.types.mods.patches.ModPatch;

import java.lang.reflect.Field;

/**
 * The {@code @JsonAdapter} annotations in cwlib, as code. TeaVM keeps
 * annotations but not their Class-valued members, so MiniGson can't read
 * {@code JsonAdapter.value()} there. JsonAdapterTableTest checks this table
 * against the annotations with JVM reflection.
 */
final class JsonAdapterTable
{
    private JsonAdapterTable() { }

    /** A new adapter for a class annotated with @JsonAdapter, or null. */
    static Object forClass(Class<?> c)
    {
        if (c == RTranslationTable.class) return new TranslationTableSerializer();
        if (c == CreationHistory.class) return new CreationHistorySerializer();
        if (c == SlotID.class) return new SlotIDSerializer();
        if (c == Thing.class) return new ThingSerializer();
        if (c == ScriptInstance.class) return new ScriptInstanceSerializer();
        if (c == ScriptObjectUID.class) return new ScriptObjectUIDSerializer();
        if (c == PScriptName.class) return new ScriptNameSerializer();
        if (c == GUID.class) return new GUIDSerializer();
        if (c == NetworkOnlineID.class) return new NetworkOnlineIDSerializer();
        if (c == NetworkPlayerID.class) return new NetworkPlayerIDSerializer();
        if (c == ResourceDescriptor.class) return new ResourceSerializer();
        if (c == SHA1.class) return new SHA1Serializer();
        if (c == WrappedResource.class) return new WrappedResourceSerializer();
        if (c == ModPatch.class) return new PatchSerializer();
        return null;
    }

    /** A new adapter for a field annotated with @JsonAdapter, or null. */
    static Object forField(Field field)
    {
        Class<?> c = field.getDeclaringClass();
        String name = field.getName();
        if ((c == RGfxMaterial.class || c == RMaterial.class || c == PoppetMaterialOverride.class) && name.equals("soundEnum"))
            return new AudioMaterialSerializer();
        if (c == PShape.class && name.equals("soundEnumOverride")) return new AudioMaterialSerializer();
        if (c == Bone.class && name.equals("skinPoseMatrix")) return new TranslationSerializer();
        if (c == PPos.class && (name.equals("localPosition") || name.equals("worldPosition")))
            return new TranslationSerializer();
        return null;
    }
}
