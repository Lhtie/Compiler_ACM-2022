package Backend;

import LLVMIR.BasicBlock;
import LLVMIR.Class;
import LLVMIR.Function;
import LLVMIR.Module;
import LLVMIR.Stmt.Stmt;

import java.io.PrintStream;

public class IRPrinter implements Pass{
    private PrintStream os;

    public IRPrinter(PrintStream os_){
        os = os_;
    }

    @Override
    public void visitModule(Module module) {
        module.gVars.forEach(this::visitStmt);
        if (module.cls.size() > 0)
            os.println();
        module.cls.forEach(this::visitClass);
        module.fns.forEach(x -> {
            os.println();
            visitFunction(x);
        });
    }

    @Override
    public void visitClass(Class cl) {
        os.print("%" + cl.identifier + " = type { ");
        for (int i = 0; i < cl.vars.size(); ++i){
            if (i > 0) os.print(", ");
            os.print(cl.vars.get(i));
        }
        os.println(" }");
    }

    @Override
    public void visitFunction(Function fn) {
        os.print("define " + fn.retType + " " + "@" + fn.identifier + "(");
        for (int i = 0; i < fn.parameters.size(); ++i){
            if (i > 0) os.print(", ");
            os.print(fn.parameters.get(i));
        }
        os.println(") {");
        visitBasicBlock(fn.entry);
        fn.blocks.forEach(x -> {
            os.println(x.label.getValue() + ":");
            visitBasicBlock(x);
        });
        os.println("}");
    }

    @Override
    public void visitBasicBlock(BasicBlock block) {
        block.stmts.forEach(x -> {
            os.print("  ");
            visitStmt(x);
        });
    }

    @Override
    public void visitStmt(Stmt stmt) {
        os.println(stmt);
    }
}
