package lbpjs.teavm;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * Build-time tweak for TeaVM: redirects {@code Math.fma}, which TeaVM's class
 * library doesn't have (JOML calls it), to {@link Fma}.
 */
public class CwlibPlugin implements TeaVMPlugin, ClassHolderTransformer
{
    @Override
    public void install(TeaVMHost host)
    {
        host.add(this);
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context)
    {
        for (MethodHolder method : cls.getMethods())
        {
            Program program = method.getProgram();
            if (program == null) continue;
            for (int i = 0; i < program.basicBlockCount(); ++i)
            {
                BasicBlock block = program.basicBlockAt(i);
                for (Instruction instruction : block)
                {
                    if (!(instruction instanceof InvokeInstruction)) continue;
                    InvokeInstruction invoke = (InvokeInstruction) instruction;
                    MethodReference target = invoke.getMethod();
                    String owner = target.getClassName();
                    if (target.getName().equals("fma")
                        && (owner.equals("java.lang.Math") || owner.equals("java.lang.StrictMath")))
                    {
                        invoke.setMethod(new MethodReference(Fma.class.getName(), target.getDescriptor()));
                    }
                }
            }
        }
    }
}
