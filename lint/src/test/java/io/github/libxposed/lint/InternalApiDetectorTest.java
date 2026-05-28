package io.github.libxposed.lint;

import com.android.tools.lint.checks.infrastructure.LintDetectorTest;
import com.android.tools.lint.checks.infrastructure.TestFile;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Issue;

import java.util.List;

public final class InternalApiDetectorTest extends LintDetectorTest {
    @Override
    protected Detector getDetector() {
        return new InternalApiDetector();
    }

    @Override
    protected List<Issue> getIssues() {
        return List.of(InternalApiDetector.ISSUE);
    }

    public void testReportsInternalMethodCall() {
        lint().files(
                moduleProp(),
                internalApiAnnotation(),
                internalApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test(FrameworkBridge bridge) {
                                bridge.attachFramework(null, () -> {});
                            }
                        }
                        """)
        ).run().expectErrorCount(1);
    }

    public void testReportsInternalClassConstructorCall() {
        lint().files(
                moduleProp(),
                internalApiAnnotation(),
                internalApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test() {
                                new FrameworkOnlyApi();
                            }
                        }
                        """)
        ).run().expectErrorCount(1);
    }

    public void testReportsInternalFieldReferenceInsideCall() {
        lint().files(
                moduleProp(),
                internalApiAnnotation(),
                internalApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test() {
                                consume(FrameworkBridge.INTERNAL_FIELD);
                            }
                        
                            private void consume(Object value) {
                            }
                        }
                        """)
        ).run().expectErrorCount(1);
    }

    public void testIgnoresInternalApiOutsideXposedModuleProject() {
        lint().files(
                internalApiAnnotation(),
                internalApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test(FrameworkBridge bridge) {
                                bridge.attachFramework(null, () -> {});
                            }
                        }
                        """)
        ).run().expectClean();
    }

    public void testIgnoresRegularApiInXposedModuleProject() {
        lint().files(
                moduleProp(),
                internalApiAnnotation(),
                internalApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test(PublicApi api) {
                                api.call();
                            }
                        }
                        """)
        ).run().expectClean();
    }

    private static TestFile moduleProp() {
        return source("src/main/resources/META-INF/xposed/module.prop", "minApiVersion=101\n");
    }

    private static TestFile internalApiAnnotation() {
        return java("src/main/java/io/github/libxposed/annotation/InternalApi.java", """
                package io.github.libxposed.annotation;
                
                public @interface InternalApi {
                }
                """);
    }

    private static TestFile internalApiStub() {
        return java("src/main/java/test/FrameworkBridge.java", """
                package test;
                
                import io.github.libxposed.annotation.InternalApi;
                
                public final class FrameworkBridge {
                    @InternalApi
                    public static final Object INTERNAL_FIELD = new Object();
                
                    @InternalApi
                    public void attachFramework(Object base, Runnable detachImpl) {
                    }
                }
                
                @InternalApi
                final class FrameworkOnlyApi {
                }
                
                final class PublicApi {
                    public void call() {
                    }
                }
                """);
    }
}
