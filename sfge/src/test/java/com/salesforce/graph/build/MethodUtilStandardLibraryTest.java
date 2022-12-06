package com.salesforce.graph.build;

import com.salesforce.TestRunner;
import com.salesforce.TestUtil;
import com.salesforce.graph.visitor.SystemDebugAccumulator;
import com.salesforce.matchers.TestRunnerMatcher;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class MethodUtilStandardLibraryTest {
    private GraphTraversalSource g;

    @BeforeEach
    public void setup() {
        this.g = TestUtil.getGraph();
    }

    @CsvSource({
        "Schema.SObjectType.Account, DescribeSObjectResult",
        "Account.SObjectType, SObjectType",
        "Schema.SObjectType.Account.Name, String",
        "Account.Name, SObjectField"
    })
    @ParameterizedTest(name = "{displayName}: {0}")
    public void testDescribeSObjectResultMatching(String initializer, String type) {
        String[] sourceCode = {
            "public class MyClass {\n"
                    + "   public static doSomething() {\n"
                    + "       callMethod("
                    + initializer
                    + ");\n"
                    + "   }\n"
                    + "   static void callMethod("
                    + type
                    + " param) {\n"
                    + "       System.debug('hello');\n"
                    + "   }\n"
                    + "}\n"
        };

        TestRunner.Result<SystemDebugAccumulator> result = TestRunner.walkPath(g, sourceCode);
        MatcherAssert.assertThat(result, TestRunnerMatcher.hasValues("hello"));
    }
}
