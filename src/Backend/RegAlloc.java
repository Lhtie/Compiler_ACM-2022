package Backend;

import Assembly.AsmBlock;
import Assembly.AsmFn;
import Assembly.AsmMod;
import Assembly.Instr.*;
import Assembly.Operand.*;
import Util.FlowGraph.AsmFlowGraph;
import Util.Graph.Node;
import Util.InterferenceGraph;
import org.antlr.v4.runtime.misc.Pair;

import java.util.*;

public class RegAlloc {
    public AsmMod topAsmMod;
    private ArrayList<Operand> t;

    public RegAlloc(AsmMod topAsmMod_){
        topAsmMod = topAsmMod_;
    }

    public void run(){
        topAsmMod.fns.forEach(fn -> {
            t = new ArrayList<>(List.of(fn.addVReg(4)));
            fn.entry.push_front(new mv(t.get(t.size() - 1), topAsmMod.calleeRegs.get(0)));
            for (int i = 1; i < topAsmMod.calleeRegs.size(); ++i) {
                t.add(fn.addVReg(4));
                fn.entry.insert_after(fn.entry.head, new mv(t.get(t.size() - 1), topAsmMod.calleeRegs.get(i)));
            }
            AsmBlock bb = fn.blocks.size() == 0 ? fn.entry : fn.blocks.get(fn.blocks.size() - 1);
            assert(topAsmMod.calleeRegs.size() == t.size());
            for (int i = t.size() - 1; i >= 0; --i)
                bb.insert_before(bb.tail, new mv(topAsmMod.calleeRegs.get(i), t.get(i)));

            new Coloring(topAsmMod, fn).Main();
        });
    }
}

final class Coloring {
    private AsmFlowGraph flowGraph;
    private InterferenceGraph interGraph;
    private AsmMod topAsmMod;
    private AsmFn fn;
    private int N, K;

    private ArrayList<Node> order;
    private HashMap<Node, BitSet> in, out;
    private HashSet<Instr> coalescedMoves, constrainedMoves, frozenMoves, worklistMoves, activeMoves;
    private HashSet<Integer> precolored, initial, simplifyWorklist, freezeWorklist, spillWorklist,
                spilledNodes, coalescedNodes, coloredNodes, newTemps;
    private ArrayList<Integer> selectStack;
    private HashMap<Integer, Long> priority;

    public Coloring(AsmMod topAsmMod_, AsmFn fn_){
        topAsmMod = topAsmMod_;
        fn = fn_;
        K = topAsmMod.calleeRegs.size() + topAsmMod.callerRegs.size();
        initial = new HashSet<>();
        for (int i = 0; i < fn.virtualRegNum; ++i)
            initial.add(i);
    }

    private int index(Reg reg){
        if (reg instanceof virtualReg){
            return ((virtualReg) reg).index;
        } else { // instanceof phyReg
            return fn.virtualRegNum + topAsmMod.regs.indexOf((phyReg) reg);
        }
    }

    private void calcPriority(AsmBlock bb){
        for (Instr i = bb.head; i != null; i = i.nxt){
            ArrayList<Reg> entity = flowGraph.def(flowGraph.node(i));
            entity.addAll(flowGraph.use(flowGraph.node(i)));
            for (Reg reg : entity) {
                long t = priority.get(index(reg)), d = 1;
                for (int p = 1; p <= flowGraph.loopDepth.get(i); ++p)
                    d *= 10;
                priority.put(index(reg), t + d);
            }
        }
    }

    private void Initial(){
        N = fn.virtualRegNum + topAsmMod.regs.size();
        precolored = new HashSet<>();
        topAsmMod.regs.forEach(reg -> precolored.add(index(reg)));
        coalescedMoves = new HashSet<>();
        constrainedMoves = new HashSet<>();
        frozenMoves = new HashSet<>();
        worklistMoves = new HashSet<>();
        activeMoves = new HashSet<>();
        simplifyWorklist = new HashSet<>();
        freezeWorklist = new HashSet<>();
        spillWorklist = new HashSet<>();
        spilledNodes = new HashSet<>();
        coalescedNodes = new HashSet<>();
        coloredNodes = new HashSet<>();
        selectStack = new ArrayList<>();
        priority = new HashMap<>();
        for (int i = 0; i < N; ++i)
            priority.put(i, 0L);
    }

    private void LivenessAnalysis(){
        flowGraph = new AsmFlowGraph(topAsmMod, fn);
        order = flowGraph.getOrder(fn.entry.head);
        calcPriority(fn.entry);
        fn.blocks.forEach(this::calcPriority);

        in = new HashMap<>();
        out = new HashMap<>();
        order.forEach(n -> {
            in.put(n, new BitSet(N));
            out.put(n, new BitSet(N));
        });
        boolean changed = true;
        for (; changed; ){
            changed = false;
            for (Node n : order) {
                BitSet inTmp = (BitSet) in.get(n).clone(), outTmp = (BitSet) out.get(n).clone();
                BitSet use = new BitSet(N), def = new BitSet(N), outn = out.get(n);
                flowGraph.use(n).forEach(x -> use.set(index(x)));
                flowGraph.def(n).forEach(x -> def.set(index(x)));
                outn.andNot(def);
                use.or(outn);
                in.put(n, use);
                if (!use.equals(inTmp)) changed = true;
                outn = new BitSet(N);
                for (Node to : n.succ)
                    outn.or(in.get(to));
                out.put(n, outn);
                if (!outn.equals(outTmp)) changed = true;
            }
        }
    }

    private void Build(){
        interGraph = new InterferenceGraph(fn.virtualRegNum, N);
        for (Node n : order) {
            Instr I = flowGraph.instr(n);
            BitSet live = (BitSet) out.get(n).clone();
            if (flowGraph.isMove(n)){
                BitSet useI = new BitSet(N);
                flowGraph.use(n).forEach(x -> useI.set(index(x)));
                live.andNot(useI);
                ArrayList<Reg> t = flowGraph.def(n);
                t.addAll(flowGraph.use(n));
                for (Reg r : t)
                    interGraph.moveList.get(index(r)).add(I);
                worklistMoves.add(I);
            }
            BitSet defI = new BitSet(N);
            flowGraph.def(n).forEach(x -> defI.set(index(x)));
            live.or(defI);
            for (Reg d : flowGraph.def(n))
                for (int l = live.nextSetBit(0); l != -1; l = live.nextSetBit(l + 1))
                    interGraph.AddEdge(l, index(d));
            // special for sw pseudo
            if (I instanceof storeOp && ((storeOp) I).symbol != null)
                interGraph.AddEdge(index((Reg) ((storeOp) I).rd), index((Reg) ((storeOp) I).rt));
        }
    }

    private void MakeWorklist(){
        for (int n : new HashSet<>(initial)){
            initial.remove(n);
            if (interGraph.degree.get(n) >= K)
                spillWorklist.add(n);
            else if (MoveRelated(n))
                freezeWorklist.add(n);
            else simplifyWorklist.add(n);
        }
    }

    private HashSet<Integer> Adjacent(int n){
        HashSet<Integer> ret = new HashSet<>(interGraph.adjList.get(n)), t = new HashSet<>(selectStack);
        t.addAll(coalescedNodes);
        ret.removeAll(t);
        return ret;
    }

    private HashSet<Instr> NodeMoves(int n){
        HashSet<Instr> ret = new HashSet<>(interGraph.moveList.get(n)), t = new HashSet<>(activeMoves);
        t.addAll(worklistMoves);
        ret.retainAll(t);
        return ret;
    }

    private boolean MoveRelated(int n){
        return !NodeMoves(n).isEmpty();
    }

    private void Simplify() {
        int n = simplifyWorklist.iterator().next();
        simplifyWorklist.remove(n);
        selectStack.add(n);
        for (int m : Adjacent(n))
            DecrementDegree(m);
    }

    private void DecrementDegree(int m){
        int d = interGraph.degree.get(m);
        interGraph.degree.put(m, d - 1);
        if (d == K){
            HashSet<Integer> nodes = Adjacent(m);
            nodes.add(m);
            EnableMoves(nodes);
            spillWorklist.remove(m);
            if (MoveRelated(m))
                freezeWorklist.add(m);
            else simplifyWorklist.add(m);
        }
    }

    private void EnableMoves(HashSet<Integer> nodes){
        for (Integer n : nodes)
            for (Instr m : NodeMoves(n))
                if (activeMoves.contains(m)){
                    activeMoves.remove(m);
                    worklistMoves.add(m);
                }
    }

    private void AddWorkList(int u){
        if (!precolored.contains(u) && !MoveRelated(u) && interGraph.degree.get(u) < K){
            freezeWorklist.remove(u);
            simplifyWorklist.add(u);
        }
    }

    private boolean OK(int t, int r){
        return interGraph.degree.get(t) < K || precolored.contains(t) ||
                interGraph.adjSet.contains(new Pair<>(t, r));
    }

    private boolean Conservation(HashSet<Integer> nodes){
        int k = 0;
        for (int n : nodes)
            if (interGraph.degree.get(n) >= K)
                k++;
        return k < K;
    }

    private void Coalesce(){
        for (Instr m : new HashSet<>(worklistMoves)){
            int u = GetAlias(index((Reg) ((mv) m).rd));
            int v = GetAlias(index((Reg) ((mv) m).rs));
            if (precolored.contains(v)){
                int t = u; u = v; v = t;
            }
            worklistMoves.remove(m);
            if (u == v){
                coalescedMoves.add(m);
                AddWorkList(u);
            } else if (precolored.contains(v) || interGraph.adjSet.contains(new Pair<>(u, v))){
                constrainedMoves.add(m);
                AddWorkList(u);
                AddWorkList(v);
            } else {
                boolean ok = true;
                if (precolored.contains(u))
                    for (int t : Adjacent(v)) ok &= OK(t, u);
                HashSet<Integer> adj = new HashSet<>();
                if (!precolored.contains(u)) {
                    adj.addAll(Adjacent(u));
                    adj.addAll(Adjacent(v));
                }
                if (precolored.contains(u) && ok || !precolored.contains(u) && Conservation(adj)){
                    coalescedMoves.add(m);
                    Combine(u, v);
                    AddWorkList(u);
                } else activeMoves.add(m);
            }
        }
    }

    private void Combine(int u, int v){
        if (freezeWorklist.contains(v))
            freezeWorklist.remove(v);
        else spillWorklist.remove(v);
        coalescedNodes.add(v);
        interGraph.alias.put(v, u);
        interGraph.moveList.get(u).addAll(interGraph.moveList.get(v));
        EnableMoves(new HashSet<>(v));
        for (int t : Adjacent(v)){
            interGraph.AddEdge(t, u);
            DecrementDegree(t);
        }
        if (interGraph.degree.get(u) >= K && freezeWorklist.contains(u)){
            freezeWorklist.remove(u);
            spillWorklist.add(u);
        }
    }

    private int GetAlias(int n){
        if (coalescedNodes.contains(n))
            return GetAlias(interGraph.alias.get(n));
        else return n;
    }

    private void Freeze(){
        int u = freezeWorklist.iterator().next();
        freezeWorklist.remove(u);
        simplifyWorklist.add(u);
        FreezeMove(u);
    }

    private void FreezeMove(int u){
        for (Instr m : NodeMoves(u)){
            int x = index((Reg) ((mv) m).rd), y = index((Reg) ((mv) m).rs), v;
            if (GetAlias(y) == GetAlias(u))
                v = GetAlias(x);
            else v = GetAlias(y);
            activeMoves.remove(m);
            frozenMoves.add(m);
            if (freezeWorklist.contains(v) && NodeMoves(v).isEmpty()){
                freezeWorklist.remove(v);
                simplifyWorklist.add(v);
            }
        }
    }

    private void SelectSpill(){
        int m = -1;
        double cheapestCost = 0;
        for (int n : spillWorklist){
            double cost = priority.get(n) * 0.1 / interGraph.degree.get(n);
            if (m == -1 || cost < cheapestCost) {
                m = n;
                cheapestCost = cost;
            }
        }
        spillWorklist.remove(m);
        simplifyWorklist.add(m);
        FreezeMove(m);
    }

    private void AssignColors(){
        while (!selectStack.isEmpty()){
            int n = selectStack.get(selectStack.size() - 1);
            selectStack.remove(selectStack.size() - 1);
            HashSet<Integer> okColors = new HashSet<>();
            for (int i = topAsmMod.regs.size() - K; i < topAsmMod.regs.size(); ++i)
                okColors.add(i);
            for (int w : interGraph.adjList.get(n)) {
                int Alias = GetAlias(w);
                if (coloredNodes.contains(Alias) || precolored.contains(Alias))
                    okColors.remove(interGraph.color.get(Alias));
            }
            if (okColors.isEmpty())
                spilledNodes.add(n);
            else {
                coloredNodes.add(n);
                interGraph.color.put(n, okColors.iterator().next());
            }
        }
        for (int n : coalescedNodes)
            interGraph.color.put(n, interGraph.color.get(GetAlias(n)));
    }

    private  void CreateTemp(AsmBlock bb){
        phyReg s0 = topAsmMod.regs.get(8);
        for (Instr i = bb.head, nxt; i != null; i = nxt){
            nxt = i.nxt;
            ArrayList<Reg> def = new ArrayList<>();
            for (Reg reg : i.def())
                if (spilledNodes.contains(index(reg))) {
                    virtualReg v = fn.addVReg(4);
                    def.add(v);
                    newTemps.add(index(v));
                    fn.alloc(v, v.bytes);
                    int imm = -fn.stackOffset.get(v);
                    if (-2048 <= imm && imm < 2048)
                        bb.insert_after(i, new storeOp(v.bytes, v, s0, new imm(imm)));
                    else {
                        virtualReg t = fn.addVReg(4);
                        bb.insert_after(i, new storeOp(v.bytes, v, t, new imm(0)));
                        bb.insert_after(i, new RCalcOp(RCalcOp.RType.ADD, t, s0, t));
                        bb.insert_after(i, new li(t, new imm(imm)));
                    }
                } else def.add(reg);
            i.push_def(def);
            ArrayList<Reg> use = new ArrayList<>();
            for (Reg reg : i.use())
                if (spilledNodes.contains(index(reg))) {
                    virtualReg v = fn.addVReg(4);
                    use.add(v);
                    newTemps.add(index(v));
                    fn.alloc(v, v.bytes);
                    int imm = -fn.stackOffset.get(v);
                    if (-2048 <= imm && imm < 2048)
                        bb.insert_before(i, new loadOp(v.bytes, v, s0, new imm(imm)));
                    else {
                        bb.insert_before(i, new li(v, new imm(imm)));
                        bb.insert_before(i, new RCalcOp(RCalcOp.RType.ADD, v, s0, v));
                        bb.insert_before(i, new loadOp(v.bytes, v, v, new imm(0)));
                    }
                } else use.add(reg);
            i.push_use(use);
        }
    }

    private void RewriteProgram() {
        newTemps = new HashSet<>();
        CreateTemp(fn.entry);
        fn.blocks.forEach(this::CreateTemp);
        initial = coloredNodes;
        initial.addAll(coalescedNodes);
        initial.addAll(newTemps);
    }

    private void Replace(AsmBlock bb) {
        for (Instr i = bb.head; i != null; i = i.nxt){
            ArrayList<Reg> def = new ArrayList<>();
            for (Reg reg : i.def())
                if (!precolored.contains(index(reg)))
                    def.add(topAsmMod.regs.get(interGraph.color.get(GetAlias(index(reg)))));
                else def.add(reg);
            i.push_def(def);
            ArrayList<Reg> use = new ArrayList<>();
            for (Reg reg : i.use())
                if (!precolored.contains(index(reg)))
                    use.add(topAsmMod.regs.get(interGraph.color.get(GetAlias(index(reg)))));
                else use.add(reg);
            i.push_use(use);
        }
    }

    public void Main(){
        Initial();
        LivenessAnalysis();
        Build();
        MakeWorklist();
        for (; ;){
            if (!simplifyWorklist.isEmpty()) Simplify();
            else if (!worklistMoves.isEmpty()) Coalesce();
            else if (!freezeWorklist.isEmpty()) Freeze();
            else if (!spillWorklist.isEmpty()) SelectSpill();
            else break;
        }
        AssignColors();
        if (!spilledNodes.isEmpty()){
            RewriteProgram();
            this.Main();
        } else {
            Replace(fn.entry);
            fn.blocks.forEach(this::Replace);
        }
    }
}