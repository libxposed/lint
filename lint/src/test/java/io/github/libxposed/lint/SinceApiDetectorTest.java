package io.github.libxposed.lint;

import com.android.tools.lint.checks.infrastructure.LintDetectorTest;
import com.android.tools.lint.checks.infrastructure.TestFile;
import com.android.tools.lint.checks.infrastructure.TestMode;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Issue;

import java.util.List;

public final class SinceApiDetectorTest extends LintDetectorTest {
    @Override
    protected Detector getDetector() {
        return new SinceApiDetector();
    }

    @Override
    protected List<Issue> getIssues() {
        return List.of(SinceApiDetector.ISSUE);
    }

    public void testReportsUnguardedNewApiUsage() {
        lint().files(
                moduleProp(),
                sinceApiAnnotation(),
                newApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            void test(NewApi api) {
                                api.newApi();
                            }
                        }
                        """)
        ).run().expectErrorCount(1);
    }

    public void testAcceptsEarlyReturnApiVersionGuard() {
        lint().files(
                moduleProp(),
                sinceApiAnnotation(),
                newApiStub(),
                java("src/main/java/test/TestModule.java", """
                        package test;
                        
                        final class TestModule {
                            int apiVersion;
                        
                            void test(NewApi api) {
                                if (apiVersion < 102) return;
                                api.newApi();
                            }
                        }
                        """)
        ).run().expectClean();
    }

    public void testAcceptsKotlinEarlyReturnApiVersionGuard() {
        lint().files(
                moduleProp(),
                sinceApiAnnotation(),
                newApiStub(),
                kotlin("src/main/java/test/TestModule.kt", """
                        package test
                        
                        class TestModule {
                            val apiVersion = 101
                        
                            fun test(api: NewApi) {
                                if (apiVersion < 102) return
                                api.newApi()
                            }
                        }
                        """)
        ).skipTestModes(TestMode.IF_TO_WHEN).run().expectClean();
    }

    private static TestFile moduleProp() {
        return source("src/main/resources/META-INF/xposed/module.prop", "minApiVersion=101\n");
    }

    private static TestFile sinceApiAnnotation() {
        return java("src/main/java/io/github/libxposed/annotation/SinceApi.java", """
                package io.github.libxposed.annotation;
                
                public @interface SinceApi {
                    int value();
                }
                """);
    }

    private static TestFile newApiStub() {
        return java("src/main/java/test/NewApi.java", """
                package test;
                
                import io.github.libxposed.annotation.SinceApi;
                
                public final class NewApi {
                    @SinceApi(102)
                    public void newApi() {
                    }
                }
                """);
    }
}
