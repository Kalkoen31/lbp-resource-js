package lbpjs.json;

import com.google.gson.annotations.JsonAdapter;
import cwlib.types.data.GUID;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** JsonAdapterTable must say exactly what cwlib's @JsonAdapter annotations say. */
public class JsonAdapterTableTest
{
    @Test
    public void matchesAnnotations() throws Exception
    {
        File jar = new File(GUID.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int classes = 0, fields = 0;
        try (JarFile file = new JarFile(jar))
        {
            for (JarEntry entry : Collections.list(file.entries()))
            {
                String name = entry.getName();
                if (!name.startsWith("cwlib/") || !name.endsWith(".class")) continue;
                Class<?> c;
                try { c = Class.forName(name.replace('/', '.').substring(0, name.length() - 6), false, getClass().getClassLoader()); }
                catch (Throwable ex) { continue; }

                JsonAdapter annotation = c.getAnnotation(JsonAdapter.class);
                Object adapter = JsonAdapterTable.forClass(c);
                if (annotation == null) assertNull(adapter, c.getName());
                else
                {
                    assertNotNull(adapter, c.getName() + " has @JsonAdapter(" + annotation.value().getSimpleName() + ")");
                    assertEquals(annotation.value(), adapter.getClass(), c.getName());
                    assertTrue(annotation.nullSafe());
                    classes++;
                }

                Field[] declared;
                try { declared = c.getDeclaredFields(); }
                catch (Throwable ex) { continue; }
                for (Field field : declared)
                {
                    JsonAdapter fieldAnnotation = field.getAnnotation(JsonAdapter.class);
                    Object fieldAdapter = JsonAdapterTable.forField(field);
                    String where = c.getName() + "." + field.getName();
                    if (fieldAnnotation == null) assertNull(fieldAdapter, where);
                    else
                    {
                        assertNotNull(fieldAdapter, where + " has @JsonAdapter(" + fieldAnnotation.value().getSimpleName() + ")");
                        assertEquals(fieldAnnotation.value(), fieldAdapter.getClass(), where);
                        fields++;
                    }
                }
            }
        }
        assertEquals(14, classes);
        assertEquals(7, fields);
    }
}
