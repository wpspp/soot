package soot.dotnet.members.method;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/*-
 * #%L
 * Soot - a J*va Optimization Framework
 * %%
 * Copyright (C) 2022 Fraunhofer SIT
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import soot.Body;
import soot.Immediate;
import soot.Local;
import soot.LocalGenerator;
import soot.PrimType;
import soot.RefType;
import soot.Scene;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.UnitPatchingChain;
import soot.Value;
import soot.VoidType;
import soot.dexpler.TrapMinimizer;
import soot.dotnet.instructions.CilBlockContainer;
import soot.dotnet.members.DotnetMethod;
import soot.dotnet.proto.ProtoIlInstructions;
import soot.dotnet.types.DotnetTypeFactory;
import soot.jimple.AssignStmt;
import soot.jimple.CastExpr;
import soot.jimple.ConditionExpr;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.toolkits.base.Aggregator;
import soot.jimple.toolkits.scalar.ConditionalBranchFolder;
import soot.jimple.toolkits.scalar.ConstantCastEliminator;
import soot.jimple.toolkits.scalar.CopyPropagator;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.jimple.toolkits.scalar.IdentityCastEliminator;
import soot.jimple.toolkits.scalar.IdentityOperationEliminator;
import soot.jimple.toolkits.scalar.NopEliminator;
import soot.jimple.toolkits.scalar.UnconditionalBranchFolder;
import soot.jimple.toolkits.scalar.UnreachableCodeEliminator;
import soot.toolkits.exceptions.TrapTightener;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.LocalPacker;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnusedLocalEliminator;

/**
 * Represents a .NET Method Body A method body starts with a BlockContainer, which contains Blocks, which have IL
 * Instructions .NET Method Body (with ILSpy AST) -> BlockContainer -> Block -> IL Instruction
 */
public class DotnetBody {

  private final ProtoIlInstructions.IlFunctionMsg ilFunctionMsg;
  private JimpleBody jb;

  public BlockEntryPointsManager blockEntryPointsManager;
  public DotnetBodyVariableManager variableManager;

  /**
   * Get method signature of this method body
   *
   * @return method signature
   */
  public DotnetMethod getDotnetMethodSig() {
    return dotnetMethodSig;
  }

  private final DotnetMethod dotnetMethodSig;

  public DotnetBody(DotnetMethod methodSignature, ProtoIlInstructions.IlFunctionMsg ilFunctionMsg) {
    this.dotnetMethodSig = methodSignature;
    this.ilFunctionMsg = ilFunctionMsg;
    blockEntryPointsManager = new BlockEntryPointsManager();
  }

  public void jimplify(JimpleBody jb) {
    this.jb = jb;
    variableManager = new DotnetBodyVariableManager(this, this.jb);
    // resolve initial variable assignments
    addThisStmt();
    variableManager.fillMethodParameter();
    variableManager.addInitLocalVariables(ilFunctionMsg.getVariablesList());

    // Resolve .NET Method Body -> BlockContainer -> Block -> IL Instruction
    CilBlockContainer blockContainer = new CilBlockContainer(ilFunctionMsg.getBody(), this);
    Body b = blockContainer.jimplify();
    for (Local l : b.getLocals()) {
      if (!jb.getLocals().contains(l)) {
        jb.getLocals().add(l);
      }
    }
    jb.getUnits().addAll(b.getUnits());
    jb.getTraps().addAll(b.getTraps());
    blockEntryPointsManager.swapGotoEntriesInJBody(jb);

    // We now do similar kind of optimizations than for dex code, since
    // the code we generate is not really efficient...

    UnconditionalBranchFolder.v().transform(jb);

    LocalPacker.v().transform(jb);
    UnusedLocalEliminator.v().transform(jb);
    // PackManager.v().getTransform("jb.lns").apply(jb);

    TrapTightener.v().transform(jb);
    TrapMinimizer.v().transform(jb);
    Aggregator.v().transform(jb);

    ConditionalBranchFolder.v().transform(jb);

    // Remove unnecessary typecasts
    ConstantCastEliminator.v().transform(jb);
    IdentityCastEliminator.v().transform(jb);

    // Remove unnecessary logic operations
    IdentityOperationEliminator.v().transform(jb);

    // We need to run this transformer since the conditional branch folder
    // might have rendered some code unreachable (well, it was unreachable
    // before as well, but we didn't know).
    UnreachableCodeEliminator.v().transform(jb);

    TransformIntsToBooleans.v().transform(jb);
    CopyPropagator.v().transform(jb);

    TransformIntsToBooleans.v().transform(jb);
    CopyPropagator.v().transform(jb);
    rewriteConditionsToIfs(jb);

    removeDeadNewExpr(jb);
    DeadAssignmentEliminator.v().transform(jb);
    UnusedLocalEliminator.v().transform(jb);

    ConditionalBranchFolder.v().transform(jb);

    UnconditionalBranchFolder.v().transform(jb);

    NopEliminator.v().transform(jb);

    // Sadly, the original contributer made everything relying on the names, so
    // we rename that mess now...
    Set<String> names = new HashSet<>();
    int id = 0;
    for (Local l : jb.getLocals()) {
      if (!names.add(l.getName())) {
        l.setName(l.getName() + "_" + id++);
      }
    }

  }

  protected void removeDeadNewExpr(JimpleBody jb) {
    UnitPatchingChain up = jb.getUnits();
    Iterator<Unit> it = up.iterator();
    ExceptionalUnitGraph g = new ExceptionalUnitGraph(jb);
    SimpleLocalUses ld = new SimpleLocalUses(g, new SimpleLocalDefs(g));
    while (it.hasNext()) {
      Unit u = it.next();
      if (u instanceof AssignStmt) {
        AssignStmt assign = (AssignStmt) u;
        if (assign.getRightOp() instanceof NewExpr) {
          if (ld.getUsesOf(assign).isEmpty()) {
            up.remove(assign);
          }

        }
      }
    }
  }

  protected void rewriteConditionsToIfs(JimpleBody jb) {
    UnitPatchingChain up = jb.getUnits();
    Unit u = up.getFirst();
    Jimple j = Jimple.v();
    while (u != null) {
      Unit next = up.getSuccOf(u);
      if (u instanceof AssignStmt) {
        AssignStmt assign = (AssignStmt) u;
        if (assign.getRightOp() instanceof ConditionExpr) {
          // e.g. foo = a == b;
          // this is not valid in Jimple...
          AssignStmt assignTrue = j.newAssignStmt(assign.getLeftOp(), j.newBooleanConstant(true));
          AssignStmt assignFalse = j.newAssignStmt(assign.getLeftOp(), j.newBooleanConstant(false));
          IfStmt ifs = j.newIfStmt(assign.getRightOp(), assignTrue);
          up.insertBefore(Arrays.asList(ifs, assignFalse, j.newGotoStmt(next), assignTrue), assign);
          up.remove(assign);

        }
      }
      u = next;
    }
  }

  protected Value createTempVar(Body jb, final Jimple jimple, Value inv) {
    Local interimLocal = variableManager.localGenerator.generateLocal(inv.getType());
    jb.getLocals().add(interimLocal);
    jb.getUnits().add(jimple.newAssignStmt(interimLocal, inv));
    return interimLocal;
  }

  private void addThisStmt() {
    if (dotnetMethodSig.isStatic()) {
      return;
    }
    RefType thisType = dotnetMethodSig.getDeclaringClass().getType();
    Local l = Jimple.v().newLocal("this", thisType);
    IdentityStmt identityStmt = Jimple.v().newIdentityStmt(l, Jimple.v().newThisRef(thisType));
    this.jb.getLocals().add(l);
    this.jb.getUnits().add(identityStmt);
  }

  /**
   * Due to three address code, inline cast expr
   *
   * @param v
   * @return
   */
  public static Value inlineCastExpr(Value v) {
    if (v instanceof Immediate) {
      return v;
    }
    if (v instanceof CastExpr) {
      return inlineCastExpr(((CastExpr) v).getOp());
    }
    return v;
  }

  public static JimpleBody getEmptyJimpleBody(SootMethod m) {
    JimpleBody b = Jimple.v().newBody(m);
    resolveEmptyJimpleBody(b, m);
    return b;
  }

  public static void resolveEmptyJimpleBody(JimpleBody b, SootMethod m) {
    // if not static add this stmt
    if (!m.isStatic()) {
      RefType thisType = m.getDeclaringClass().getType();
      Local l = Jimple.v().newLocal("this", thisType);
      IdentityStmt identityStmt = Jimple.v().newIdentityStmt(l, Jimple.v().newThisRef(thisType));
      b.getLocals().add(l);
      b.getUnits().add(identityStmt);
    }
    // parameters
    for (int i = 0; i < m.getParameterCount(); i++) {
      Type parameterType = m.getParameterType(i);
      Local paramLocal = Jimple.v().newLocal("arg" + i, parameterType);
      b.getLocals().add(paramLocal);
      b.getUnits().add(Jimple.v().newIdentityStmt(paramLocal, Jimple.v().newParameterRef(parameterType, i)));
    }
    LocalGenerator lg = Scene.v().createLocalGenerator(b);
    b.getUnits().add(Jimple.v().newThrowStmt(lg.generateLocal(soot.RefType.v("java.lang.Throwable"))));
    if (m.getReturnType() instanceof VoidType) {
      b.getUnits().add(Jimple.v().newReturnVoidStmt());
    } else if (m.getReturnType() instanceof PrimType) {
      b.getUnits().add(Jimple.v().newReturnStmt(DotnetTypeFactory.initType(m.getReturnType())));
    } else {
      b.getUnits().add(Jimple.v().newReturnStmt(NullConstant.v()));
    }
  }

}
