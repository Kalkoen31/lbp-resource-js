package lbpjs.teavm;

import org.teavm.extension.spi.substitution.SimpleSubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

/**
 * Classes TeaVM compiles from lbpjs.subst.* instead of the originals:
 * <ul>
 *   <li>{@code sun.misc.Unsafe}: JOML probes it and falls back to NIO buffers
 *       when it fails, which the stub makes it do.</li>
 *   <li>{@code cwlib.singleton.ResourceSystem}: the toolkit's GUI/archive state
 *       (threads, temp folders, Swing). Resources only ask it for the
 *       translation table, which a web page doesn't have.</li>
 * </ul>
 */
public class Substitutions extends SimpleSubstitutionPolicy
{
    @Override
    public void contribute(SubstitutionSink sink)
    {
        sink.selectClasses(SimpleSubstitutionPolicy.<String>named("sun.misc.Unsafe")
                .or(SimpleSubstitutionPolicy.<String>named("cwlib.singleton.ResourceSystem")))
            .packagePrefix("lbpjs.subst.")
            .dontFallbackWhenNoSubstitution();
    }
}
