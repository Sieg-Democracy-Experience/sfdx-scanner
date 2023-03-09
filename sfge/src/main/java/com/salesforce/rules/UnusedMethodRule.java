package com.salesforce.rules;

import com.salesforce.apex.jorje.ASTConstants;
import com.salesforce.apex.jorje.ASTConstants.NodeType;
import com.salesforce.graph.Schema;
import com.salesforce.graph.ops.PathEntryPointUtil;
import com.salesforce.graph.ops.directive.EngineDirective;
import com.salesforce.graph.vertex.MethodVertex;
import com.salesforce.graph.vertex.SFVertexFactory;
import com.salesforce.rules.unusedmethod.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class UnusedMethodRule extends AbstractStaticRule {
    private static final String URL =
            "https://forcedotcom.github.io/sfdx-scanner/en/v3.x/salesforce-graph-engine/rules/#UnusedMethodRule";
    private static final Logger LOGGER = LogManager.getLogger(UnusedMethodRule.class);
    private static final String DESCRIPTION = "Identifies methods that are not invoked";
    private static final String VIOLATION_TEMPLATE = "Method %s in class %s is never invoked";

    GraphTraversalSource g;
    /** A helper object used to track state and caching as the rule executes. */
    RuleStateTracker ruleStateTracker;

    private UnusedMethodRule() {
        super();
    }

    public static UnusedMethodRule getInstance() {
        return LazyHolder.INSTANCE;
    }

    @Override
    protected int getSeverity() {
        return SEVERITY.LOW.code;
    }

    @Override
    protected String getDescription() {
        return DESCRIPTION;
    }

    @Override
    protected String getCategory() {
        return CATEGORY.PERFORMANCE.name;
    }

    @Override
    protected String getUrl() {
        return URL;
    }

    @Override
    protected boolean isEnabled() {
        return true;
    }

    public RuleStateTracker getRuleStateTracker() {
        return ruleStateTracker;
    }

    @Override
    protected List<Violation> _run(
            GraphTraversalSource g, GraphTraversal<Vertex, Vertex> eligibleVertices) {
        reset(g);
        List<MethodVertex> candidateVertices = getCandidateVertices(eligibleVertices);
        seekMethodUsages(candidateVertices);
        return convertMethodsToViolations();
    }

    /** Reset the rule's state to prepare for a subsequent execution. */
    private void reset(GraphTraversalSource g) {
        this.g = g;
        this.ruleStateTracker = new RuleStateTracker(g);
    }

    /**
     * Get a list of all method vertices on non-standard types. All such methods are candidates for
     * analysis.
     */
    private List<MethodVertex> getCandidateVertices(
            GraphTraversal<Vertex, Vertex> eligibleVertices) {
        return SFVertexFactory.loadVertices(
                g, eligibleVertices.hasLabel(NodeType.METHOD).hasNot(Schema.IS_STANDARD));
    }

    /**
     * Seek an invocation of each provided method, unless the method is deemed to be ineligible for
     * analysis. Eligible and unused methods are tracked in {@link #ruleStateTracker}.
     */
    private void seekMethodUsages(List<MethodVertex> candidateVertices) {
        for (MethodVertex candidateVertex : candidateVertices) {
            // If the method is one that isn't eligible to be analyzed, skip it.
            if (methodIsIneligible(candidateVertex)) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Skipping vertex "
                                    + candidateVertex.getName()
                                    + ", as it is ineligible for analysis");
                }
                continue;
            }

            // If the method was determined as eligible, track it as such.
            ruleStateTracker.trackEligibleMethod(candidateVertex);

            // Depending on the kind of method, we should instantiate a different call validator.
            BaseMethodCallValidator validator;
            if (candidateVertex.isStatic()) {
                validator = new StaticMethodCallValidator(candidateVertex, ruleStateTracker);
            } else if (candidateVertex.isConstructor()) {
                validator = new ConstructorMethodCallValidator(candidateVertex, ruleStateTracker);
            } else {
                validator = new InstanceMethodCallValidator(candidateVertex, ruleStateTracker);
            }
            // If the validator can't find any usage, then we should add the method as unused.
            if (!validator.usageDetected()) {
                ruleStateTracker.trackUnusedMethod(candidateVertex);
            }
        }
    }

    /** Convert every known unused method to a violation, and return them in a list. */
    private List<Violation> convertMethodsToViolations() {
        return ruleStateTracker.getUnusedMethods().stream()
                .map(
                        m ->
                                new Violation.StaticRuleViolation(
                                        String.format(
                                                VIOLATION_TEMPLATE,
                                                m.getName(),
                                                m.getDefiningType()),
                                        m))
                .collect(Collectors.toList());
    }

    /**
     * Returns true if the provided method isn't a valid candidate for analysis by this rule. Used
     * for filtering the list of all possible candidates into just the eligible ones.
     */
    private boolean methodIsIneligible(MethodVertex vertex) {
        // TODO: At this time, only static methods, private instance methods, and private/protected
        //       constructors are supported. This limit will be loosened over time, and eventually
        //       removed entirely.
        if (!vertex.isStatic()
                && !(vertex.isPrivate() || (vertex.isProtected() && vertex.isConstructor()))) {
            return true;
        }

        // Test methods are ineligible.
        if (vertex.isTest()) {
            return true;
        }

        // The "<clinit>" method is inherently ineligible.
        if (vertex.getName().equalsIgnoreCase("<clinit>")) {
            return true;
        }

        // If we're directed to skip this method, obviously we should do so.
        if (directedToSkip(vertex)) {
            return true;
        }

        // Abstract methods must be implemented by all child classes.
        // This rule can detect if those implementations are unused, and another rule exists to
        // detect unused abstract classes and interface themselves. As such, inspecting
        // abstract methods directly is unnecessary.
        if (vertex.isAbstract()) {
            return true;
        }

        // Private constructors with arity of 0 are ineligible. Creating such a constructor is a
        // standard way of preventing utility classes whose only methods are static from being
        // instantiated at all, so including such methods in our analysis is likely to generate
        // more false positives than true positives.
        if (vertex.isConstructor() && vertex.isPrivate() && vertex.getArity() == 0) {
            return true;
        }

        // Methods whose name starts with this prefix are getters/setters. Getters are typically
        // used by VF controllers, and setters are frequently made private to render a property
        // immutable. As such, inspecting these methods is likely to generate false or noisy
        // positives.
        if (vertex.getName().toLowerCase().startsWith(ASTConstants.PROPERTY_METHOD_PREFIX)) {
            return true;
        }

        // Finally, path entry points should be skipped, because they're definitionally publicly
        // accessible, and therefore we must assume that they're used somewhere or other.
        // But if the method isn't a path entry point, then it's eligible.
        return PathEntryPointUtil.isPathEntryPoint(vertex);
    }

    /**
     * Helper method for {@link #methodIsIneligible(MethodVertex)}. Indicates whether a method is
     * annotated with an engine directive denoting that it should be skipped by this rule.
     */
    private boolean directedToSkip(MethodVertex methodVertex) {
        List<EngineDirective> directives = methodVertex.getAllEngineDirectives();
        for (EngineDirective directive : directives) {
            if (directive.isAnyDisable()
                    && directive.matchesRule(this.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static final class LazyHolder {
        // Postpone initialization until first use.
        private static final UnusedMethodRule INSTANCE = new UnusedMethodRule();
    }
}
