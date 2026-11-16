package lbpjs.teavm;

import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/**
 * Tells TeaVM which reflection to keep: instance fields (for the JSON layer)
 * and no-argument constructors (cwlib's Serializer creates structs with
 * {@code clazz.getDeclaredConstructor().newInstance()}) of cwlib and JOML classes.
 * Methods stay out, so reflection doesn't pull in code that can't compile.
 */
public class CwlibReflection extends SimpleReflectionPolicy
{
    @Override
    protected void setup()
    {
        for (String pkg : new String[] { "cwlib", "org.joml" })
            selectPackage(pkg, true)
                .reflectableFields(INSTANCE)
                .reflectableMethods(m -> m.isConstructor() && m.parameters().isEmpty());
    }
}
