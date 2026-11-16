package lbpjs.teavm;

import org.teavm.extension.spi.resources.ResourcesPolicy;

import java.util.Collection;

/**
 * Classpath resources to embed in the TeaVM build: cwlib's LBP2 to LBP1 GUID
 * substitution tables, which RGuidSubst loads when levels are saved.
 */
public class CwlibResources implements ResourcesPolicy
{
    private static final String[] RESOURCES = { "poppet_lbp1.txt", "gmat_lbp1.txt" };

    @Override
    public String[] supplyResources(Collection<? extends String> classpathResources)
    {
        return RESOURCES;
    }
}
