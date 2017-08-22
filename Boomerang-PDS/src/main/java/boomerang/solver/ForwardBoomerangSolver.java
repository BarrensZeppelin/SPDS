package boomerang.solver;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import com.beust.jcommander.internal.Sets;

import boomerang.jimple.Field;
import boomerang.jimple.ReturnSite;
import boomerang.jimple.Statement;
import heros.InterproceduralCFG;
import soot.Body;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import sync.pds.solver.SyncPDSSolver;
import sync.pds.solver.nodes.ExclusionNode;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.NodeWithLocation;
import sync.pds.solver.nodes.PopNode;
import sync.pds.solver.nodes.PushNode;
import wpds.impl.PushRule;
import wpds.impl.Rule;
import wpds.impl.Weight;
import wpds.interfaces.State;
import wpds.interfaces.WPAUpdateListener;

public class ForwardBoomerangSolver extends AbstractBoomerangSolver {
	public ForwardBoomerangSolver(InterproceduralCFG<Unit, SootMethod> icfg) {
		super(icfg);
	}

	@Override
	public Collection<? extends State> computeCallFlow(SootMethod caller, ReturnSite returnSite, InvokeExpr invokeExpr,
			Value fact, SootMethod callee, Stmt calleeSp) {
		if (!callee.hasActiveBody())
			return Collections.emptySet();
		Body calleeBody = callee.getActiveBody();
		if (invokeExpr instanceof InstanceInvokeExpr) {
			InstanceInvokeExpr iie = (InstanceInvokeExpr) invokeExpr;
			if (iie.getBase().equals(fact) && !callee.isStatic()) {
				return Collections.singleton(new PushNode<Statement, Value, Statement>(new Statement(calleeSp, callee),
						calleeBody.getThisLocal(), returnSite, PDSSystem.CALLS));
			}
		}
		int i = 0;
		for (Value arg : invokeExpr.getArgs()) {
			if (arg.equals(fact)) {
				Local param = calleeBody.getParameterLocal(i);
				return Collections.singleton(new PushNode<Statement, Value, Statement>(new Statement(calleeSp, callee),
						param, returnSite, PDSSystem.CALLS));
			}
			i++;
		}
		return Collections.emptySet();
	}

	@Override
	protected boolean killFlow(SootMethod m, Stmt curr, Value value) {
		if (!m.getActiveBody().getLocals().contains(value))
			return true;
		if (curr instanceof AssignStmt) {
			AssignStmt as = (AssignStmt) curr;
			// Kill x at any statement x = * during propagation.
			if (as.getLeftOp().equals(value)) {
				// But not for a statement x = x.f
				if (as.getRightOp() instanceof InstanceFieldRef) {
					InstanceFieldRef iie = (InstanceFieldRef) as.getRightOp();
					if (iie.getBase().equals(value)) {
						return false;
					}
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public Collection<State> computeNormalFlow(SootMethod method, Stmt curr, Value fact, Stmt succ) {
		Set<State> out = Sets.newHashSet();
		if (!isFieldWriteWithBase(curr, fact)) {
			// always maintain data-flow if not a field write // killFlow has
			// been taken care of
			out.add(new Node<Statement, Value>(new Statement((Stmt) succ, method), fact));
		} else {
			out.add(new ExclusionNode<Statement, Value, Field>(new Statement(succ, method), fact,
					getWrittenField(curr)));
		}
		if (curr instanceof AssignStmt) {
			AssignStmt assignStmt = (AssignStmt) curr;
			Value leftOp = assignStmt.getLeftOp();
			Value rightOp = assignStmt.getRightOp();
			if (rightOp.equals(fact)) {
				if (leftOp instanceof InstanceFieldRef) {
					InstanceFieldRef ifr = (InstanceFieldRef) leftOp;
					out.add(new PushNode<Statement, Value, Field>(new Statement(succ, method), ifr.getBase(),
							new Field(ifr.getField()), PDSSystem.FIELDS));
				} else {
					out.add(new Node<Statement, Value>(new Statement(succ, method), leftOp));
				}
			}
			if (rightOp instanceof InstanceFieldRef) {
				InstanceFieldRef ifr = (InstanceFieldRef) rightOp;
				Value base = ifr.getBase();
				if (base.equals(fact)) {
					NodeWithLocation<Statement, Value, Field> succNode = new NodeWithLocation<>(
							new Statement(succ, method), leftOp, new Field(ifr.getField()));
					out.add(new PopNode<NodeWithLocation<Statement, Value, Field>>(succNode, PDSSystem.FIELDS));
				}
			}
		}
		return out;
	}

	@Override
	public Collection<? extends State> computeReturnFlow(SootMethod method, Stmt curr, Value value, Stmt callSite,
			Stmt returnSite) {
		if (curr instanceof ReturnStmt) {
			Value op = ((ReturnStmt) curr).getOp();
			if (op.equals(value)) {
				if(callSite instanceof AssignStmt){
					return Collections.singleton(new PopNode<Value>(((AssignStmt)callSite).getLeftOp(), PDSSystem.CALLS));
				}
			}
		}
		if (!method.isStatic()) {
			if (value.equals(method.getActiveBody().getThisLocal())) {
				if (callSite.containsInvokeExpr()) {
					if (callSite.getInvokeExpr() instanceof InstanceInvokeExpr) {
						InstanceInvokeExpr iie = (InstanceInvokeExpr) callSite.getInvokeExpr();
						return Collections.singleton(new PopNode<Value>(iie.getBase(), PDSSystem.CALLS));
					}
				}
			}
		}
		int index = 0;
		for (Local param : method.getActiveBody().getParameterLocals()) {
			if (param.equals(value)) {
				if (callSite.containsInvokeExpr()) {
					InstanceInvokeExpr iie = (InstanceInvokeExpr) callSite.getInvokeExpr();
					return Collections.singleton(new PopNode<Value>(iie.getArg(index), PDSSystem.CALLS));
				}
			}
			index++;
		}

		return Collections.emptySet();
	}

}