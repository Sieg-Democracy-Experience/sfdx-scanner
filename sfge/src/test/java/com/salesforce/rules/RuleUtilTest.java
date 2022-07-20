package com.salesforce.rules;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import com.salesforce.TestUtil;
import com.salesforce.graph.Schema;
import com.salesforce.graph.vertex.MethodVertex;
import com.salesforce.metainfo.MetaInfoCollectorTestProvider;
import com.salesforce.metainfo.VisualForceHandlerImpl;
import com.salesforce.rules.AbstractRuleRunner.RuleRunnerTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RuleUtilTest {
    private static final Logger LOGGER = LogManager.getLogger(RuleUtilTest.class);
    private GraphTraversalSource g;

    @BeforeEach
    public void setup() {
        this.g = TestUtil.getGraph();
    }

    @ValueSource(
            strings = {
                Schema.AURA_ENABLED,
                Schema.INVOCABLE_METHOD,
                Schema.REMOTE_ACTION,
                Schema.NAMESPACE_ACCESSIBLE
            })
    @ParameterizedTest(name = "{displayName}: {0}")
    public void getPathEntryPoints_includesAnnotatedMethods(String annotation) {
        String sourceCode =
                "public class Foo {\n"
                        + "	@"
                        + annotation
                        + "\n"
                        + "	public boolean annotatedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonAnnotatedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        TestUtil.buildGraph(g, sourceCode, true);

        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g);

        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(1)));
        MethodVertex firstVertex = entryPoints.get(0);
        assertEquals("annotatedMethod", firstVertex.getName());
    }

    @Test
    public void getPathEntryPoints_includesGlobalMethods() {
        String sourceCode =
                "public class Foo {\n"
                        + "    global static void globalStaticMethod() {\n"
                        + "    }\n"
                        + "    global void globalInstanceMethod() {\n"
                        + "    }\n"
                        + "    public static void publicStaticMethod() {\n"
                        + "    }\n"
                        + "}\n";
        TestUtil.buildGraph(g, sourceCode, true);

        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g);

        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(2)));
        boolean staticMethodFound = false;
        boolean instanceMethodFound = false;
        for (MethodVertex entrypoint : entryPoints) {
            switch (entrypoint.getName()) {
                case "globalStaticMethod":
                    staticMethodFound = true;
                    break;
                case "globalInstanceMethod":
                    instanceMethodFound = true;
                    break;
                default:
                    fail("Unexpected method " + entrypoint.getName());
            }
        }
        assertTrue(staticMethodFound);
        assertTrue(instanceMethodFound);
    }

    @Test
    public void getPathEntryPoints_includesPageReferenceMethods() {
        String sourceCode =
                "public class Foo {\n"
                        + "	public PageReference pageRefMethod() {\n"
                        + "		return null;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonAuraMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        TestUtil.buildGraph(g, sourceCode, true);

        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g);

        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(1)));
        MethodVertex firstVertex = entryPoints.get(0);
        assertEquals("pageRefMethod", firstVertex.getName());
    }

    @Test
    public void getPathEntryPoints_includesInboundEmailHandlerMethods() {
        String sourceCode =
                "public class MyClass implements Messaging.InboundEmailHandler {\n"
                        + "    public Messaging.InboundEmailResult handleInboundEmail(Messaging.InboundEmail email, Messaging.InboundEnvelope envelope) {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "    public Messaging.InboundEmailHandler someSecondaryMethod() {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n";
        TestUtil.buildGraph(g, sourceCode, true);

        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g);

        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(1)));
        MethodVertex firstVertex = entryPoints.get(0);
        assertEquals("handleInboundEmail", firstVertex.getName());
    }

    @Test
    public void getPathEntryPoints_includesExposedControllerMethods() {
        try {
            String controllerSourceCode =
                    "public class ApexControllerClass {\n"
                            + "	public String getSomeStringProperty() {\n"
                            + "		return 'beep';\n"
                            + "	}\n"
                            + "\n"
                            + "	global String getSomeOtherStringProperty() {\n"
                            + "		return 'boop';\n"
                            + "	}\n"
                            + "\n"
                            + "	private String getYetAnotherStringProperty() {\n"
                            + "		return 'baap';\n"
                            + "	}\n"
                            + "}\n";

            MetaInfoCollectorTestProvider.setVisualForceHandler(
                    new VisualForceHandlerImpl() {
                        private TreeSet<String> references =
                                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

                        @Override
                        public void loadProjectFiles(List<String> sourceFolders) {
                            // NO-OP
                        }

                        @Override
                        public TreeSet<String> getMetaInfoCollected() {
                            references.add("ApexControllerClass");
                            return references;
                        }
                    });
            TestUtil.buildGraph(g, controllerSourceCode, true);

            List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g);
            // TODO: This number is three, because the synthetic clone method is included. This
            // might not be the behavior
            //  we want. If we change our minds, the test should change.
            MatcherAssert.assertThat(entryPoints, hasSize(equalTo(3)));
            List<String> methodNames =
                    entryPoints.stream().map(MethodVertex::getName).collect(Collectors.toList());
            MatcherAssert.assertThat(
                    methodNames,
                    containsInAnyOrder(
                            "clone", "getSomeStringProperty", "getSomeOtherStringProperty"));
        } finally {
            MetaInfoCollectorTestProvider.removeVisualForceHandler();
        }
    }

    @Test
    public void getPathEntryPoints_includeMethodLevelTargets() {
        String sourceCode0 =
                "public class MyClass1 {\n"
                        + "	@AuraEnabled\n"
                        + "	public boolean auraMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonIncludedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "	public boolean nonIncludedMethod(boolean param) {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        String sourceCode1 =
                "public class MyClass2 {\n"
                        + "	public PageReference pageRefMethod() {\n"
                        + "		return null;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonIncludedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "	public boolean nonIncludedMethod(boolean param) {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        TestUtil.buildGraph(g, new String[] {sourceCode0, sourceCode1}, true);
        List<RuleRunnerTarget> targets = new ArrayList<>();
        // Create a target that encompasses both of the `nonIncludedMethod()` definitions in
        // MyClass1.
        targets.add(
                TestUtil.createTarget("TestCode0", Collections.singletonList("nonIncludedMethod")));

        // TEST: Load the methods encompassed by the targets.
        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g, targets);
        // Make sure the right number of methods were returned.
        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(2)));

        // Sort the vertices, so we can inspect them.
        entryPoints.sort(
                Comparator.comparing(MethodVertex::getDefiningType)
                        .thenComparing(MethodVertex::getName)
                        .thenComparing(MethodVertex::getBeginLine));
        // Make sure that the methods returned were the right ones.
        MethodVertex firstVertex = entryPoints.get(0);
        assertEquals("nonIncludedMethod", firstVertex.getName());
        assertEquals("MyClass1", firstVertex.getDefiningType());
        assertEquals(7, firstVertex.getBeginLine());

        MethodVertex secondVertex = entryPoints.get(1);
        assertEquals("nonIncludedMethod", secondVertex.getName());
        assertEquals("MyClass1", secondVertex.getDefiningType());
        assertEquals(10, secondVertex.getBeginLine());
    }

    @Test
    public void getPathEntryPoints_includeMethodAndFileLevelTargets() {
        String sourceCode0 =
                "public class MyClass1 {\n"
                        + "	@AuraEnabled\n"
                        + "	public boolean auraMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonIncludedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "	public boolean nonIncludedMethod(boolean param) {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        String sourceCode1 =
                "public class MyClass2 {\n"
                        + "	public PageReference pageRefMethod() {\n"
                        + "		return null;\n"
                        + "	}\n"
                        + "\n"
                        + "	public boolean nonIncludedMethod() {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "	public boolean nonIncludedMethod(boolean param) {\n"
                        + "		return true;\n"
                        + "	}\n"
                        + "}\n";
        TestUtil.buildGraph(g, new String[] {sourceCode0, sourceCode1}, true);
        List<RuleRunnerTarget> targets = new ArrayList<>();
        // Create a target that encompasses both of the `nonIncludedMethod()` definitions in
        // MyClass1.
        targets.add(
                TestUtil.createTarget("TestCode0", Collections.singletonList("nonIncludedMethod")));
        // Create a target that encompasses the entirety of MyClass2.
        targets.add(TestUtil.createTarget("TestCode1", new ArrayList<>()));

        // TEST: Load the methods encompassed by the targets.
        List<MethodVertex> entryPoints = RuleUtil.getPathEntryPoints(g, targets);

        // Make sure the right number of methods were returned.
        MatcherAssert.assertThat(entryPoints, hasSize(equalTo(3)));
        // Sort the vertices, so we can inspect them.
        entryPoints.sort(
                Comparator.comparing(MethodVertex::getDefiningType)
                        .thenComparing(MethodVertex::getName)
                        .thenComparing(MethodVertex::getBeginLine));
        // Make sure that the methods returned were the right ones.
        MethodVertex firstVertex = entryPoints.get(0);
        assertEquals("nonIncludedMethod", firstVertex.getName());
        assertEquals("MyClass1", firstVertex.getDefiningType());
        assertEquals(7, firstVertex.getBeginLine());

        MethodVertex secondVertex = entryPoints.get(1);
        assertEquals("nonIncludedMethod", secondVertex.getName());
        assertEquals("MyClass1", secondVertex.getDefiningType());
        assertEquals(10, secondVertex.getBeginLine());

        MethodVertex thirdVertex = entryPoints.get(2);
        assertEquals("pageRefMethod", thirdVertex.getName());
        assertEquals("MyClass2", thirdVertex.getDefiningType());
        assertEquals(2, thirdVertex.getBeginLine());
    }

    @Test
    public void getAllRules_noExceptionThrown() {
        try {
            List<AbstractRule> allRules = RuleUtil.getEnabledRules();
            MatcherAssert.assertThat(allRules, hasSize(1));
            assertTrue(allRules.contains(ApexFlsViolationRule.getInstance()));
        } catch (Exception ex) {
            fail("Unexpected " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Test
    public void getRule_RealRuleReturned() {
        try {
            AbstractRule realRule = RuleUtil.getRule(UnusedAbstractClassRule.class.getSimpleName());
            MatcherAssert.assertThat(realRule, not(nullValue()));
        } catch (RuleUtil.RuleNotFoundException rnfe) {
            fail("No exception should be thrown when a real rule is requested");
        }
    }

    @Test
    public void getRule_FakeRuleThrowsErr() {
        try {
            AbstractRule fakeRule = RuleUtil.getRule("DefinitelyAFakeRule");
            fail("Exception should have been thrown when a non-existent rule is requested");
        } catch (RuleUtil.RuleNotFoundException rnfe) {
            // Nothing is needed in this catch block, since merely entering it confirms the test.
        }
    }
}
