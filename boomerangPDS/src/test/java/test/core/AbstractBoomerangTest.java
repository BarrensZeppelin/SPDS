/*******************************************************************************
 * Copyright (c) 2018 Fraunhofer IEM, Paderborn, Germany.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *  
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Johannes Spaeth - initial API and implementation
 *******************************************************************************/
package test.core;

import boomerang.*;
import boomerang.callgraph.CalleeListener;
import boomerang.callgraph.ObservableICFG;
import boomerang.callgraph.ObservableStaticICFG;
import boomerang.debugger.CallGraphDebugger;
import boomerang.debugger.Debugger;
import boomerang.debugger.IDEVizDebugger;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.preanalysis.PreTransformBodies;
import boomerang.results.BackwardBoomerangResults;
import boomerang.seedfactory.SeedFactory;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.util.AccessPath;
import boomerang.util.AccessPathParser;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import heros.utilities.DefaultValueMap;
import org.junit.Rule;
import org.junit.rules.Timeout;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import sync.pds.solver.OneWeightFunctions;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import test.core.selfrunning.AbstractTestingFramework;
import wpds.impl.Transition;
import wpds.impl.Weight.NoWeight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class AbstractBoomerangTest extends AbstractTestingFramework {

	private static final boolean FAIL_ON_IMPRECISE = false;
	private static final boolean VISUALIZATION = false;

	@Rule
	public Timeout timeout = new Timeout(10000000);
	private ObservableICFG<Unit, SootMethod> icfg;
	private Collection<? extends Query> allocationSites;
	protected Collection<? extends Query> queryForCallSites;
	protected Collection<Error> unsoundErrors = Sets.newHashSet();
	protected Collection<Error> imprecisionErrors = Sets.newHashSet();
	protected boolean resultsMustNotBeEmpty = false;
	private boolean accessPathQuery = false;
	private Set<AccessPath> expectedAccessPaths = Sets.newHashSet();

	private boolean integerQueries;
	private SeedFactory<NoWeight> seedFactory;

	protected int analysisTimeout = 3000 *1000;

	public enum AnalysisMode {
		WholeProgram, DemandDrivenBackward;
	}

	protected AnalysisMode[] getAnalyses() {
		return new AnalysisMode[] {
				 AnalysisMode.WholeProgram,
				AnalysisMode.DemandDrivenBackward
				};
	}

	protected SceneTransformer createAnalysisTransformer() {
		PackManager.v().getPack("wjtp").add(new Transform("wjtp.prepare", new PreTransformBodies()));
		return new SceneTransformer() {

			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				icfg = new ObservableStaticICFG(new JimpleBasedInterproceduralCFG());
				seedFactory = new SeedFactory<NoWeight>(){


					@Override
					public ObservableICFG<Unit, SootMethod> icfg() {
						return icfg;
					}

					@Override
					protected Collection<? extends Query> generate(SootMethod method, Stmt u, Collection calledMethods) {
						Optional<? extends Query> query = new FirstArgumentOf("queryFor").test(u);

						if(query.isPresent()){
							return Collections.singleton(query.get());
						}
						query = new FirstArgumentOf("queryForAndNotEmpty").test(u);

						if(query.isPresent()){
							resultsMustNotBeEmpty = true;
							return Collections.singleton(query.get());
						}
						query = new FirstArgumentOf("intQueryFor").test(u);
						if(query.isPresent()){
							integerQueries = true;
							return Collections.singleton(query.get());
						}
						
						query = new FirstArgumentOf("accessPathQueryFor").test(u);
						if(query.isPresent()){
							accessPathQuery = true;
							getAllExpectedAccessPath(u, method);
							return Collections.singleton(query.get());
						}
						return Collections.emptySet();
					}

					private void getAllExpectedAccessPath(Stmt u, SootMethod m) {
						Value arg = u.getInvokeExpr().getArg(1);
						if(arg instanceof StringConstant){
							StringConstant stringConstant = (StringConstant) arg;
							String value = stringConstant.value;
							expectedAccessPaths.addAll(AccessPathParser.parseAllFromString(value,m));
						}
					}

					
				};
				queryForCallSites = seedFactory.computeSeeds();
				if(integerQueries){
					allocationSites = extractQuery(new IntegerAllocationSiteOf());
				} else{
					allocationSites = extractQuery(new AllocationSiteOf());
				}
				for(int i = 0; i< getIterations();i++) {
					for (AnalysisMode analysis : getAnalyses()) {
						switch (analysis) {
						case WholeProgram:
							if(!integerQueries)
								runWholeProgram();
							break;
						case DemandDrivenBackward:
							runDemandDrivenBackward();
							break;
						}
					}
					if(resultsMustNotBeEmpty)
						return;
					if (!unsoundErrors.isEmpty()) {
						throw new RuntimeException(Joiner.on("\n").join(unsoundErrors));
					}
					if (!imprecisionErrors.isEmpty() && FAIL_ON_IMPRECISE) {
						throw new AssertionError(Joiner.on("\n").join(imprecisionErrors));
					}
				}
			}
		};
	}

	public int getIterations() {
		return 1;
	}

	private void runDemandDrivenBackward() {
		// Run backward analysis
		Set<Node<Statement, Val>> backwardResults = runQuery(queryForCallSites);
		compareQuery(allocationSites, backwardResults, AnalysisMode.DemandDrivenBackward);
	}

	private class AllocationSiteOf implements ValueOfInterestInUnit {
		public Optional<? extends Query> test(Stmt unit) {
			if (unit instanceof AssignStmt) {
				AssignStmt as = (AssignStmt) unit;
				if (as.getLeftOp() instanceof Local && as.getRightOp() instanceof NewExpr) {
					NewExpr expr = ((NewExpr) as.getRightOp());
					if (allocatesObjectOfInterest(expr)) {
						Local local = (Local) as.getLeftOp();
						Statement statement = new Statement(unit, icfg.getMethodOf(unit));
						ForwardQuery forwardQuery = new ForwardQuery(statement, new AllocVal(local, icfg.getMethodOf(unit), as.getRightOp(),statement));
						return Optional.<Query>of(forwardQuery);
					}
				}
			}
			return Optional.absent();
		}
	}
	private class IntegerAllocationSiteOf implements ValueOfInterestInUnit {
		public Optional<? extends Query> test(Stmt stmt) {
			if (stmt instanceof AssignStmt) {
				AssignStmt as = (AssignStmt) stmt;
				if (as.getLeftOp().toString().equals("allocation")) {
					Statement statement = new Statement(stmt, icfg.getMethodOf(stmt));
					if (as.getLeftOp() instanceof Local && as.getRightOp() instanceof IntConstant) {
						Local local = (Local) as.getLeftOp();
						ForwardQuery forwardQuery = new ForwardQuery(statement,
                                new AllocVal(local, icfg.getMethodOf(stmt), as.getRightOp(),new Statement((Stmt) as,
                                        icfg.getMethodOf(stmt))));
						return Optional.<Query>of(forwardQuery);
					}

					if(as.containsInvokeExpr()){
						AtomicReference<Query> returnValue = new AtomicReference<>();
						icfg.addCalleeListener(new CalleeListener<Unit,SootMethod>(){

							@Override
							public Unit getObservedCaller() {
								return as;
							}

							@Override
							public void onCalleeAdded(Unit unit, SootMethod sootMethod) {
								for(Unit u : icfg.getEndPointsOf(sootMethod)){
									if(u instanceof ReturnStmt && ((ReturnStmt) u).getOp() instanceof IntConstant){
										ForwardQuery forwardQuery = new ForwardQuery(statement,
												new AllocVal(as.getLeftOp(), icfg.getMethodOf(stmt), ((ReturnStmt) u).getOp(), new Statement((Stmt) u, sootMethod)));
										returnValue.set(forwardQuery);
									}
								}
							}
						});
						if (returnValue.get() != null){
							return Optional.of(returnValue.get());
						}
					}
				}
			}
			
			return Optional.absent();
		}
	}
	private class FirstArgumentOf implements ValueOfInterestInUnit {

		private String methodNameMatcher;

		public FirstArgumentOf(String methodNameMatcher) {
			this.methodNameMatcher = methodNameMatcher;
		}

		@Override
		public Optional<? extends Query> test(Stmt unit) {
			Stmt stmt = (Stmt) unit;
			if (!(stmt.containsInvokeExpr()))
				return Optional.absent();
			InvokeExpr invokeExpr = stmt.getInvokeExpr();
			if (!invokeExpr.getMethod().getName().matches(methodNameMatcher))
				return Optional.absent();
			Value param = invokeExpr.getArg(0);
			if (!(param instanceof Local))
				return Optional.absent();
			SootMethod newMethod = icfg.getMethodOf(unit);
			Statement newStatement = new Statement(unit, newMethod);
			Val newVal = new Val(param, newMethod);
			BackwardQuery newBackwardQuery = new BackwardQuery(newStatement, newVal);
			return Optional.<Query>of(newBackwardQuery);
		}
	}

	private void compareQuery(Collection<? extends Query> expectedResults,
			Collection<? extends Node<Statement, Val>> results, AnalysisMode analysis) {
		System.out.println("Boomerang Results: " + results);
		System.out.println("Expected Results: " + expectedResults);
		Collection<Node<Statement, Val>> falseNegativeAllocationSites = new HashSet<>();
		for (Query res : expectedResults) {
			if (!results.contains(res.asNode()))
				falseNegativeAllocationSites.add(res.asNode());
		}
		Collection<? extends Node<Statement, Val>> falsePositiveAllocationSites = new HashSet<>(results);
		for (Query res : expectedResults) {
			falsePositiveAllocationSites.remove(res.asNode());
		}

		String answer = (falseNegativeAllocationSites.isEmpty() ? "" : "\nFN:" + falseNegativeAllocationSites)
				+ (falsePositiveAllocationSites.isEmpty() ? "" : "\nFP:" + falsePositiveAllocationSites + "\n");
		if (!falseNegativeAllocationSites.isEmpty()) {
			unsoundErrors.add(new Error(analysis + " Unsound results for:" + answer));
		}
		if (!falsePositiveAllocationSites.isEmpty())
			imprecisionErrors.add(new Error(analysis + " Imprecise results for:" + answer));
		
		if(resultsMustNotBeEmpty && results.isEmpty()){
			throw new RuntimeException("Expected some results, but Boomerang returned no allocation sites.");
		}
	}

	private Set<Node<Statement, Val>> runQuery(Collection<? extends Query> queries) {
		final Set<Node<Statement, Val>> results = Sets.newHashSet();
		
		for (final Query query : queries) {
			DefaultBoomerangOptions options = (integerQueries ? new IntAndStringBoomerangOptions() : new DefaultBoomerangOptions(){
				@Override
				public boolean arrayFlows() {
					return true;
				}
				
				@Override
				public int analysisTimeoutMS() {
					return analysisTimeout;
				}

				@Override
				public boolean onTheFlyCallGraph() {
					return false;
				}
			});
			Boomerang solver = new Boomerang(options) {
				@Override
				public ObservableICFG<Unit, SootMethod> icfg() {
					return icfg;
				}
				
				@Override
				public Debugger createDebugger() {
					return VISUALIZATION ? new IDEVizDebugger(ideVizFile, icfg()) :
							new CallGraphDebugger(dotFile, icfg.getCallGraphCopy());
				}

				@Override
				public SeedFactory<NoWeight> getSeedFactory() {
					return null;
				}
			};
			if(query instanceof BackwardQuery){
				setupSolver(solver);
				BackwardBoomerangResults<NoWeight> res = solver.solve((BackwardQuery) query);

				solver.debugOutput();

				for(ForwardQuery q : res.getAllocationSites().keySet()){
					results.add(q.asNode());

					for (Node<Statement, Val> s : solver.getSolvers().get(q).getReachedStates()) {
						if (s.stmt().getMethod().toString().contains("unreachable")) {
							throw new RuntimeException("Propagation within unreachable method found.");
						}
					}
				}

//				System.out.println(res.getAllAliases());
				if(accessPathQuery){
					checkContainsAllExpectedAccessPath(res.getAllAliases());
				}
			}
		}
		return results;
	}

	private void checkContainsAllExpectedAccessPath(Set<AccessPath> allAliases) {
		HashSet<AccessPath> expected = Sets.newHashSet(expectedAccessPaths);
		expected.removeAll(allAliases);
		if(!expected.isEmpty()){
			throw new RuntimeException("Did not find all access path! " +expected);
		}
	}

	private void runWholeProgram() {
		final Set<Node<Statement, Val>> results = Sets.newHashSet();
		WholeProgramBoomerang<NoWeight> solver = new WholeProgramBoomerang<NoWeight>(new DefaultBoomerangOptions() {
			@Override
			public int analysisTimeoutMS() {
				return analysisTimeout;
			}
			@Override
			public boolean onTheFlyCallGraph() {
				return false;
			}
		}) {
			@Override
			public ObservableICFG<Unit, SootMethod> icfg() {
				return icfg;
			}

			@Override
			public Debugger createDebugger() {
				return VISUALIZATION ? new IDEVizDebugger(ideVizFile, icfg) : new CallGraphDebugger(dotFile, icfg.getCallGraphCopy());
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, NoWeight> getForwardFieldWeights() {
				return new OneWeightFunctions<Statement, Val, Field, NoWeight>(NoWeight.NO_WEIGHT_ZERO,
						NoWeight.NO_WEIGHT_ONE);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, NoWeight> getBackwardFieldWeights() {
				return new OneWeightFunctions<Statement, Val, Field, NoWeight>(NoWeight.NO_WEIGHT_ZERO,
						NoWeight.NO_WEIGHT_ONE);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, NoWeight> getBackwardCallWeights() {
				return new OneWeightFunctions<Statement, Val, Statement, NoWeight>(NoWeight.NO_WEIGHT_ZERO,
						NoWeight.NO_WEIGHT_ONE);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, NoWeight> getForwardCallWeights(ForwardQuery sourceQuery) {
				return new OneWeightFunctions<Statement, Val, Statement, NoWeight>(NoWeight.NO_WEIGHT_ZERO,
						NoWeight.NO_WEIGHT_ONE);
			}

			@Override
			public SeedFactory<NoWeight> getSeedFactory() {
				return new SeedFactory<NoWeight>() {

						@Override
						protected Collection<? extends Query> generate(SootMethod method, Stmt u,
																	   Collection<SootMethod> calledMethods) {
							if(u instanceof AssignStmt){
								AssignStmt assignStmt = (AssignStmt) u;
								if(options.isAllocationVal(assignStmt.getRightOp())){
									return Collections.singleton(new ForwardQuery(new Statement((Stmt) u, method), new AllocVal(assignStmt.getLeftOp(),method,assignStmt.getRightOp(),new Statement((Stmt) u, method))));
								}
							}
							return Collections.emptySet();
						}

						@Override
						public ObservableICFG<Unit, SootMethod> icfg() {
							return icfg;
						}
						@Override
						protected boolean analyseClassInitializers() {
							return true;
						}
					};
			}
		};
		setupSolver(solver);
		solver.wholeProgramAnalysis();
		DefaultValueMap<Query, AbstractBoomerangSolver<NoWeight>> solvers = solver.getSolvers();
		for (final Query q : solvers.keySet()) {
			for (final Query queryForCallSite : queryForCallSites) {
				solvers.get(q).getFieldAutomaton()
						.registerListener(new WPAStateListener<Field, INode<Node<Statement, Val>>, NoWeight>(
								new SingleNode<Node<Statement, Val>>(queryForCallSite.asNode())) {

							@Override
							public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t,
									NoWeight w,
									WeightedPAutomaton<Field, INode<Node<Statement, Val>>, NoWeight> weightedPAutomaton) {
								if (t.getLabel().equals(Field.empty()) && t.getTarget().fact().equals(q.asNode())) {
									results.add(q.asNode());
								}
							}

							@Override
							public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t,
									NoWeight w,
									WeightedPAutomaton<Field, INode<Node<Statement, Val>>, NoWeight> weightedPAutomaton) {

							}
						});
			}
			for (Node<Statement, Val> s : solvers.get(q).getReachedStates()) {
				if (s.stmt().getMethod().toString().contains("unreachable")
						&& !q.toString().contains("dummyClass.main")) {
					throw new RuntimeException("Propagation within unreachable method found: " + q);
				}
			}
		}

		solver.debugOutput();
		compareQuery(allocationSites, results, AnalysisMode.WholeProgram);
		System.out.println();
	}

	protected void setupSolver(WeightedBoomerang<NoWeight> solver) {
	}

	private boolean allocatesObjectOfInterest(NewExpr rightOp) {
		SootClass interfaceType = Scene.v().getSootClass("test.core.selfrunning.AllocatedObject");
		if (!interfaceType.isInterface())
			return false;
		RefType allocatedType = rightOp.getBaseType();
		return Scene.v().getActiveHierarchy().getImplementersOf(interfaceType).contains(allocatedType.getSootClass());
	}

	private Collection<? extends Query> extractQuery(ValueOfInterestInUnit predicate) {
		Set<Query> queries = Sets.newHashSet();
		extractQuery(sootTestMethod, predicate, queries, null, new HashSet<>());
		return queries;
	}

	private void extractQuery(SootMethod m, ValueOfInterestInUnit predicate, Collection<Query> queries, Stmt callSite,
			Set<Node<SootMethod, Stmt>> visited) {
		if (!m.hasActiveBody() || visited.contains(new Node<>(m, callSite)))
			return;
		visited.add(new Node<>(m, callSite));
		Body activeBody = m.getActiveBody();
		for (Unit cs : icfg.getCallsFromWithin(m)) {
		    icfg.addCalleeListener(new CalleeListener<Unit, SootMethod>(){

				@Override
				public Unit getObservedCaller() {
					return cs;
				}

				@Override
				public void onCalleeAdded(Unit unit, SootMethod sootMethod) {
					extractQuery(sootMethod, predicate, queries, (callSite == null ? (Stmt) cs : callSite), visited);
				}
            });
		}
		for (Unit u : activeBody.getUnits()) {
			if (!(u instanceof Stmt))
				continue;
			Optional<? extends Query> optOfVal = predicate.test((Stmt) u);
			if (optOfVal.isPresent()) {
				queries.add(optOfVal.get());
			}
		}
	}

	protected Collection<String> errorOnVisitMethod() {
		return Lists.newLinkedList();
	}

	protected boolean includeJDK() {
		return true;
	}

	/**
	 * The methods parameter describes the variable that a query is issued for.
	 * Note: We misuse the @Deprecated annotation to highlight the method in the
	 * Code.
	 */

	public static void queryFor(Object variable) {

	}

	public static void accessPathQueryFor(Object variable, String aliases) {

	}
	protected void queryForAndNotEmpty(Object variable) {

	}
	protected void intQueryFor(int variable) {

	}

	/**
	 * A call to this method flags the object as at the call statement as not
	 * reachable by the analysis.
	 * 
	 * @param variable
	 */
	protected void unreachable(Object variable) {

	}

	/**
	 * This method can be used in test cases to create branching. It is not
	 * optimized away.
	 * 
	 * @return
	 */
	protected boolean staticallyUnknown() {
		return true;
	}

	private interface ValueOfInterestInUnit {
		Optional<? extends Query> test(Stmt unit);
	}
}
