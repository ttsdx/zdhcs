/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics.mosa.structural;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageFactory;
import org.evosuite.coverage.exception.ExceptionCoverageHelper;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.exception.TryCatchCoverageTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.StrongMutationTestFitness;
import org.evosuite.coverage.mutation.WeakMutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.setup.CallContext;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.evosuite.Properties.Criterion.*;

public class MultiCriteriatManager<T extends Chromosome> extends StructuralGoalManager<T>{

	private static final Logger logger = LoggerFactory.getLogger(MultiCriteriatManager.class);

	protected BranchFitnessGraph<T, FitnessFunction<T>> graph;

	protected Map<BranchCoverageTestFitness, Set<FitnessFunction<T>>> dependencies;

	protected final Map<Integer, FitnessFunction<T>> branchCoverageTrueMap = new LinkedHashMap<Integer, FitnessFunction<T>>();
	protected final Map<Integer, FitnessFunction<T>> branchCoverageFalseMap = new LinkedHashMap<Integer, FitnessFunction<T>>();
	protected final Map<String, FitnessFunction<T>> branchlessMethodCoverageMap = new LinkedHashMap<String, FitnessFunction<T>>();

	public MultiCriteriatManager(List<FitnessFunction<T>> fitnessFunctions) {
		super(fitnessFunctions);
		// initialize uncovered goals
		uncoveredGoals.addAll(fitnessFunctions);

		// initialize the dependency graph among branches
		this.graph = getControlDepencies4Branches(fitnessFunctions);

		// initialize the dependency graph between branches and other coverage targets (e.g., statements)
		// let's derive the dependency graph between branches and other coverage targets (e.g., statements)
		for (Criterion criterion : Properties.CRITERION){
			switch (criterion){
				case LINE:
					addDependencies4Line();
					break;
				case STATEMENT:
					addDependencies4Statement();
					break;
				case WEAKMUTATION:
					addDependencies4WeakMutation();
					break;
				case STRONGMUTATION:
					addDependencies4StrongMutation();
					break;
				case METHOD:
					addDependencies4Methods();
					break;
				case INPUT:
					addDependencies4Input();
					break;
				case OUTPUT:
					addDependencies4Output();
					break;
				case TRYCATCH:
					addDependencies4TryCatch();
					break;
				case METHODNOEXCEPTION:
					addDependencies4MethodsNoException();
					break;
				case CBRANCH:
					addDependencies4CBranch();
					break;
				default:
					LoggingUtils.getEvoLogger().error("The criterion {} is not currently supported in DynaMOSA", criterion.getClass());
			}
		}

		// initialize current goals
		this.currentGoals.addAll(graph.getRootBranches());
	}

	@SuppressWarnings("unchecked")
	private void addDependencies4TryCatch() {
		logger.debug("Added dependencies for Try-Catch");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof TryCatchCoverageTestFitness){
				TryCatchCoverageTestFitness stmt = (TryCatchCoverageTestFitness) ff;
				BranchCoverageTestFitness branch = new BranchCoverageTestFitness(stmt.getBranchGoal());
				this.dependencies.get(branch).add((FitnessFunction<T>) stmt);
			}
		}
	}

	private void initializeMaps(Set<FitnessFunction<T>> set){
		for (FitnessFunction<T> ff : set) {
			BranchCoverageTestFitness goal = (BranchCoverageTestFitness) ff;
			// Skip instrumented branches - we only want real branches
			if(goal.getBranch() != null) {
				if(goal.getBranch().isInstrumented()) {
					continue;
				}
			}

			if (goal.getBranch() == null) {
				branchlessMethodCoverageMap.put(goal.getClassName() + "."
						+ goal.getMethod(), ff);
			} else {
				if (goal.getBranchExpressionValue())
					branchCoverageTrueMap.put(goal.getBranch().getActualBranchId(), ff);
				else
					branchCoverageFalseMap.put(goal.getBranch().getActualBranchId(), ff);
			}
		}
	}

	private void addDependencies4Output() {
		logger.debug("Added dependencies for Output");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof OutputCoverageTestFitness){
				OutputCoverageTestFitness output = (OutputCoverageTestFitness) ff;
				ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
				BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
				if (pool.getInstructionsIn(output.getClassName(), output.getMethod()) == null)
					continue;
				for (BytecodeInstruction instruction : pool.getInstructionsIn(output.getClassName(), output.getMethod())) {
					if (instruction.getBasicBlock() != null){
						Set<ControlDependency> cds = instruction.getBasicBlock().getControlDependencies();
						if (cds.size()==0){
							this.currentGoals.add(ff);
						} else {
							for (ControlDependency cd : cds) {
								BranchCoverageTestFitness fitness = BranchCoverageFactory.createBranchCoverageTestFitness(cd);
								this.dependencies.get(fitness).add(ff);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * This methods derive the dependencies between {@link InputCoverageTestFitness} and branches. 
	 * Therefore, it is used to update 'this.dependencies'
	 */
	private void addDependencies4Input() {
		logger.debug("Added dependencies for Input");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof InputCoverageTestFitness){
				InputCoverageTestFitness input = (InputCoverageTestFitness) ff;
				ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
				BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
				if (pool.getInstructionsIn(input.getClassName(), input.getMethod()) == null)
					continue;
				for (BytecodeInstruction instruction : pool.getInstructionsIn(input.getClassName(), input.getMethod())) {
					if (instruction.getBasicBlock() != null){
						Set<ControlDependency> cds = instruction.getBasicBlock().getControlDependencies();
						if (cds.size()==0){
							this.currentGoals.add(ff);
						} else {
							for (ControlDependency cd : cds) {
								BranchCoverageTestFitness fitness = BranchCoverageFactory.createBranchCoverageTestFitness(cd);
								this.dependencies.get(fitness).add(ff);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * This methods derive the dependencies between {@link MethodCoverageTestFitness} and branches. 
	 * Therefore, it is used to update 'this.dependencies'
	 */
	@SuppressWarnings("unchecked")
	private void addDependencies4Methods() {
		logger.debug("Added dependencies for Methods");
		for (BranchCoverageTestFitness branch : this.dependencies.keySet()){
			MethodCoverageTestFitness method = new MethodCoverageTestFitness(branch.getClassName(), branch.getMethod());
			this.dependencies.get(branch).add((FitnessFunction<T>) method);
		}
	}

	/**
	 * This methods derive the dependencies between {@link MethodNoExceptionCoverageTestFitness} and branches.
	 * Therefore, it is used to update 'this.dependencies'
	 */
	@SuppressWarnings("unchecked")
	private void addDependencies4MethodsNoException() {
		logger.debug("Added dependencies for MethodsNoException");
		for (BranchCoverageTestFitness branch : this.dependencies.keySet()){
			MethodNoExceptionCoverageTestFitness method = new MethodNoExceptionCoverageTestFitness(branch.getClassName(), branch.getMethod());
			this.dependencies.get(branch).add((FitnessFunction<T>) method);
		}
	}

	/**
	 * This methods derive the dependencies between {@link CBranchTestFitness} and branches.
	 * Therefore, it is used to update 'this.dependencies'
	 */
	@SuppressWarnings("unchecked")
	private void addDependencies4CBranch() {
		logger.debug("Added dependencies for CBranch");
		CallGraph callGraph = DependencyAnalysis.getCallGraph();
		for (BranchCoverageTestFitness branch : this.dependencies.keySet()) {
			for (CallContext context : callGraph.getMethodEntryPoint(branch.getClassName(), branch.getMethod())) {
				CBranchTestFitness cBranch = new CBranchTestFitness(branch.getBranchGoal(), context);
				this.dependencies.get(branch).add((FitnessFunction<T>) cBranch);
				logger.debug("Added context branch: " + cBranch.toString());
			}
		}
	}

	/**
	 * This methods derive the dependencies between {@link WeakMutationTestFitness} and branches. 
	 * Therefore, it is used to update 'this.dependencies'
	 */
	private void addDependencies4WeakMutation() {
		logger.debug("Added dependencies for Weak-Mutation");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof WeakMutationTestFitness){
				WeakMutationTestFitness mutation = (WeakMutationTestFitness) ff;
				Set<BranchCoverageGoal> goals = mutation.getMutation().getControlDependencies();
				if (goals.size() == 0){
					this.currentGoals.add(ff);
				} else {
					for (BranchCoverageGoal goal : goals) {
						BranchCoverageTestFitness fitness = new BranchCoverageTestFitness(goal);
						this.dependencies.get(fitness).add(ff);
					}
				}
			}
		}
	}

	/**
	 * This methods derive the dependencies between {@link org.evosuite.coverage.mutation.StrongMutationTestFitness} and branches.
	 * Therefore, it is used to update 'this.dependencies'
	 */
	private void addDependencies4StrongMutation() {
		logger.debug("Added dependencies for Strong-Mutation");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof StrongMutationTestFitness){
				StrongMutationTestFitness mutation = (StrongMutationTestFitness) ff;
				Set<BranchCoverageGoal> goals = mutation.getMutation().getControlDependencies();
				if (goals.size() == 0){
					this.currentGoals.add(ff);
				} else {
					for (BranchCoverageGoal goal : goals) {
						BranchCoverageTestFitness fitness = new BranchCoverageTestFitness(goal);
						this.dependencies.get(fitness).add(ff);
					}
				}
			}
		}
	}

	/**
	 * This methods derive the dependencies between  {@link LineCoverageTestFitness} and branches. 
	 * Therefore, it is used to update 'this.dependencies'
	 */
	private void addDependencies4Line() {
		logger.debug("Added dependencies for Lines");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof LineCoverageTestFitness){
				LineCoverageTestFitness line = (LineCoverageTestFitness) ff;
				ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
				BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
				BytecodeInstruction instruction = pool.getFirstInstructionAtLineNumber(line.getClassName(), line.getMethod(), line.getLine());
				Set<ControlDependency> cds = instruction.getControlDependencies();
				if(cds.size() == 0)
					this.currentGoals.add(ff);
				else {
					for (ControlDependency cd : cds) {
						BranchCoverageTestFitness fitness = BranchCoverageFactory.createBranchCoverageTestFitness(cd);
						this.dependencies.get(fitness).add(ff);
					}
				}
			}
		}
	}

	/**
	 * This methods derive the dependencies between  {@link StatementCoverageTestFitness} and branches. 
	 * Therefore, it is used to update 'this.dependencies'
	 */
	@SuppressWarnings("unchecked")
	private void addDependencies4Statement() {
		logger.debug("Added dependencies for Statements");
		for (FitnessFunction<T> ff : this.uncoveredGoals){
			if (ff instanceof StatementCoverageTestFitness){
				StatementCoverageTestFitness stmt = (StatementCoverageTestFitness) ff;
				if (stmt.getBranchFitnesses().size() == 0)
					this.currentGoals.add(ff);
				else {
					for (BranchCoverageTestFitness branch : stmt.getBranchFitnesses()) {
						this.dependencies.get(branch).add((FitnessFunction<T>) stmt);
					}
				}
			}
		}
	}



	@SuppressWarnings("unchecked")
	@Override
	public void calculateFitness(T c) {
		// run the test
		TestCase test = ((TestChromosome) c).getTestCase();
		ExecutionResult result = TestCaseExecutor.runTest(test);
		((TestChromosome) c).setLastExecutionResult(result);
		c.setChanged(false);

		if (result.hasTimeout() || result.hasTestException()){
			for (FitnessFunction<T> f : currentGoals)
				c.setFitness(f, Double.MAX_VALUE);
			return;
		}

		// 1) we update the set of currents goals
		Set<FitnessFunction<T>> visitedTargets = new LinkedHashSet<FitnessFunction<T>>(uncoveredGoals.size()*2);
		LinkedList<FitnessFunction<T>> targets = new LinkedList<FitnessFunction<T>>();
		targets.addAll(this.currentGoals);

		while (targets.size()>0){
			FitnessFunction<T> fitnessFunction = targets.poll();

			int past_size = visitedTargets.size();
			visitedTargets.add(fitnessFunction);
			if (past_size == visitedTargets.size())
				continue;

			double value = fitnessFunction.getFitness(c);
			if (value == 0.0) {
				updateCoveredGoals(fitnessFunction, c);
				if (fitnessFunction instanceof BranchCoverageTestFitness){
					for (FitnessFunction<T> child : graph.getStructuralChildren(fitnessFunction)){
						targets.addLast(child);
					}
					for (FitnessFunction<T> dependentTarget : dependencies.get(fitnessFunction)){
						targets.addLast(dependentTarget);
					}
				}
			} else {
				currentGoals.add(fitnessFunction);
			}	
		}
		currentGoals.removeAll(coveredGoals.keySet());
		updateArchive(c, result);
	}

	public void updateArchive(T c, ExecutionResult result){
		// 2) we update the archive
		for (Integer branchid : result.getTrace().getCoveredFalseBranches()){
			FitnessFunction<T> branch = this.branchCoverageFalseMap.get(branchid);
			if (branch == null)
				continue;
			updateCoveredGoals((FitnessFunction<T>) branch, c);
		}
		for (Integer branchid : result.getTrace().getCoveredTrueBranches()){
			FitnessFunction<T> branch = this.branchCoverageTrueMap.get(branchid);
			if (branch == null)
				continue;
			updateCoveredGoals((FitnessFunction<T>) branch, c);
		}
		for (String method : result.getTrace().getCoveredBranchlessMethods()){
			FitnessFunction<T> branch = this.branchlessMethodCoverageMap.get(method);
			if (branch == null)
				continue;
			updateCoveredGoals((FitnessFunction<T>) branch, c);
		}

		// let's manage the exception coverage
		if (ArrayUtil.contains(Properties.CRITERION, EXCEPTION)){
			// if one of the coverage criterion is Criterion.EXCEPTION,
			// then we have to analyze the results of the execution do look
			// for generated exceptions
			Set<ExceptionCoverageTestFitness> set = deriveCoveredExceptions(c);
			for (ExceptionCoverageTestFitness exp : set){
				// let's update the list of fitness functions
				updateCoveredGoals((FitnessFunction<T>) exp, c);
				// new covered exceptions (goals) have to be added to the archive
				if (!ExceptionCoverageFactory.getGoals().containsKey(exp.getKey())){
					// let's update the newly discovered exceptions to ExceptionCoverageFactory
					ExceptionCoverageFactory.getGoals().put(exp.getKey(), exp);
				}
			}
		}
	}

	/**
	 * This method analyzes the execution results of a TestChromosome looking for generated exceptions.
	 * Such exceptions are converted in instances of the class {@link ExceptionCoverageTestFitness},
	 * which are additional covered goals when using as criterion {@link Properties.Criterion Exception}
	 * @param t TestChromosome to analyze
	 * @return list of exception goals being covered by t
	 */
	public Set<ExceptionCoverageTestFitness> deriveCoveredExceptions(T t){
		Set<ExceptionCoverageTestFitness> covered_exceptions = new LinkedHashSet<ExceptionCoverageTestFitness>();
		TestChromosome testCh = (TestChromosome) t;
		ExecutionResult result = testCh.getLastExecutionResult();
		
		if(result.calledReflection())
			return covered_exceptions;

		for (Integer i : result.getPositionsWhereExceptionsWereThrown()) {
			if(ExceptionCoverageHelper.shouldSkip(result,i)){
				continue;
			}

			Class<?> exceptionClass = ExceptionCoverageHelper.getExceptionClass(result,i);
			String methodIdentifier = ExceptionCoverageHelper.getMethodIdentifier(result, i); //eg name+descriptor
			boolean sutException = ExceptionCoverageHelper.isSutException(result,i); // was the exception originated by a direct call on the SUT?

			/*
			 * We only consider exceptions that were thrown by calling directly the SUT (not the other
			 * used libraries). However, this would ignore cases in which the SUT is indirectly tested
			 * through another class
			 */

			if (sutException) {

				ExceptionCoverageTestFitness.ExceptionType type = ExceptionCoverageHelper.getType(result,i);
				/*
				 * Add goal to list of fitness functions to solve
				 */
				ExceptionCoverageTestFitness goal = new ExceptionCoverageTestFitness(Properties.TARGET_CLASS, methodIdentifier, exceptionClass, type);
				covered_exceptions.add(goal);
			}
		}
		return covered_exceptions;
	}

	public BranchFitnessGraph getControlDepencies4Branches(List<FitnessFunction<T>> fitnessFunctions){
		Set<FitnessFunction<T>> setOfBranches = new LinkedHashSet<FitnessFunction<T>>();
		this.dependencies = new LinkedHashMap();

		List<BranchCoverageTestFitness> branches = new BranchCoverageFactory().getCoverageGoals();
		for (BranchCoverageTestFitness branch : branches){
			setOfBranches.add((FitnessFunction<T>) branch);
			this.dependencies.put(branch, new LinkedHashSet<FitnessFunction<T>>());
		}

		// initialize the maps
		this.initializeMaps(setOfBranches);

		return new BranchFitnessGraph<T, FitnessFunction<T>>(setOfBranches);
	}
}
