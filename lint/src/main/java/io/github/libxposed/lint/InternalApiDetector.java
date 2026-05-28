package io.github.libxposed.lint;

import com.android.tools.lint.client.api.UElementHandler;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.SourceCodeScanner;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.USimpleNameReferenceExpression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reports libxposed APIs that are reserved for Xposed framework implementations.
 */
public final class InternalApiDetector extends Detector implements SourceCodeScanner {
    /**
     * Creates the detector used by Android lint.
     */
    public InternalApiDetector() {
    }

    static final Issue ISSUE = Issue.create(
            "XposedInternalApi",
            "Internal Xposed API used by a module",
            "This API element is annotated with `@InternalApi` and is reserved for Xposed " +
                    "framework implementations. Xposed modules must not call it.",
            Category.CORRECTNESS,
            6,
            Severity.ERROR,
            new Implementation(InternalApiDetector.class, Scope.JAVA_FILE_SCOPE)
    );

    private static final String INTERNAL_API = "io.github.libxposed.annotation.InternalApi";

    private final Map<Path, Boolean> moduleProjectCache = new HashMap<>();

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return Arrays.asList(UCallExpression.class, USimpleNameReferenceExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(@NotNull JavaContext context) {
        return new UElementHandler() {
            @Override
            public void visitCallExpression(@NotNull UCallExpression node) {
                checkUsage(context, node, node.resolve());
            }

            @Override
            public void visitSimpleNameReferenceExpression(@NotNull USimpleNameReferenceExpression node) {
                var resolved = node.resolve();
                if (resolved instanceof PsiMethod) {
                    return;
                }
                if (resolved instanceof PsiClass && isInsideCallExpression(node)) {
                    return;
                }
                if (resolved instanceof PsiModifierListOwner owner) {
                    checkUsage(context, node, owner);
                }
            }
        };
    }

    private static boolean isInsideCallExpression(UElement node) {
        var current = node.getUastParent();
        while (current != null) {
            if (current instanceof UCallExpression) {
                return true;
            }
            current = current.getUastParent();
        }
        return false;
    }

    private void checkUsage(JavaContext context, UElement node, PsiModifierListOwner owner) {
        if (owner == null || !isXposedModuleProject(context)) {
            return;
        }
        if (findInternalApiOwner(owner) == null) {
            return;
        }

        context.report(ISSUE, node, context.getLocation(node),
                "This Xposed API is reserved for framework implementations and must not be called by modules.");
    }

    private boolean isXposedModuleProject(JavaContext context) {
        var projectDir = context.getProject().getDir().toPath();
        return moduleProjectCache.computeIfAbsent(projectDir, InternalApiDetector::hasModuleProp);
    }

    private static boolean hasModuleProp(Path projectDir) {
        var srcDir = projectDir.resolve("src");
        if (!Files.isDirectory(srcDir)) {
            return false;
        }

        var modulePropSuffix = Path.of("META-INF", "xposed", "module.prop");
        try (Stream<Path> paths = Files.walk(srcDir, 8)) {
            return paths
                    .filter(Files::isRegularFile)
                    .anyMatch(path -> path.endsWith(modulePropSuffix));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static PsiModifierListOwner findInternalApiOwner(PsiModifierListOwner owner) {
        if (owner == null) {
            return null;
        }
        if (owner.getAnnotation(INTERNAL_API) != null) {
            return owner;
        }

        if (owner instanceof PsiClass psiClass) {
            return findInternalApiOwner(psiClass.getContainingClass());
        }
        if (owner instanceof PsiMethod method) {
            return findInternalApiOwner(method.getContainingClass());
        }
        if (owner instanceof PsiField field) {
            return findInternalApiOwner(field.getContainingClass());
        }
        return null;
    }
}
