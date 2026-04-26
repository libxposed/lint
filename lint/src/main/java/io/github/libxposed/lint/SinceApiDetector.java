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
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNamedElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UReferenceExpression;
import org.jetbrains.uast.USimpleNameReferenceExpression;
import org.jetbrains.uast.UastBinaryOperator;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reports libxposed API usages that require a higher Xposed API than the module declares.
 */
public final class SinceApiDetector extends Detector implements SourceCodeScanner {
    /**
     * Creates the detector used by Android lint.
     */
    public SinceApiDetector() {
    }

    static final Issue ISSUE = Issue.create(
            "XposedNewApi",
            "Newer Xposed API used without an API version check",
            "This API element is annotated with `@SinceApi` and may be unavailable on frameworks " +
                    "older than the module's `minApiVersion`. Guard the usage with `getApiVersion()` " +
                    "or raise `minApiVersion` in `META-INF/xposed/module.prop`.",
            Category.CORRECTNESS,
            6,
            Severity.ERROR,
            new Implementation(SinceApiDetector.class, Scope.JAVA_FILE_SCOPE)
    );

    private static final String SINCE_API = "io.github.libxposed.annotation.SinceApi";
    private static final int UNKNOWN_API = -1;
    private static final Pattern API_AT_LEAST_PATTERN = Pattern.compile(
            "\\b(?:getApiVersion\\s*\\(\\s*\\)|apiVersion)\\s*(>=|>|==)\\s*((?:[\\w.$]+\\.)?API_(\\d+)|\\d+)\\b"
    );
    private static final Pattern API_AT_LEAST_REVERSED_PATTERN = Pattern.compile(
            "\\b((?:[\\w.$]+\\.)?API_(\\d+)|\\d+)\\s*(<=|<|==)\\s*(?:getApiVersion\\s*\\(\\s*\\)|apiVersion)\\b"
    );
    private static final Pattern API_BELOW_PATTERN = Pattern.compile(
            "\\b(?:getApiVersion\\s*\\(\\s*\\)|apiVersion)\\s*(<|<=)\\s*((?:[\\w.$]+\\.)?API_(\\d+)|\\d+)\\b"
    );
    private static final Pattern API_BELOW_REVERSED_PATTERN = Pattern.compile(
            "\\b((?:[\\w.$]+\\.)?API_(\\d+)|\\d+)\\s*(>|>=)\\s*(?:getApiVersion\\s*\\(\\s*\\)|apiVersion)\\b"
    );

    private final Map<Path, Integer> minApiCache = new HashMap<>();

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return Arrays.asList(UCallExpression.class, USimpleNameReferenceExpression.class, UMethod.class);
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
                if (resolved instanceof PsiModifierListOwner owner) {
                    checkUsage(context, node, owner);
                }
            }

            @Override
            public void visitMethod(@NotNull UMethod node) {
                var superMethod = context.getEvaluator().getSuperMethod(node.getJavaPsi());
                if (superMethod != null) {
                    checkUsage(context, node, superMethod);
                }
            }
        };
    }

    private void checkUsage(JavaContext context, UElement node, PsiModifierListOwner owner) {
        if (owner == null) {
            return;
        }
        if (owner instanceof PsiField field && field.computeConstantValue() != null) {
            return;
        }
        var minApi = getMinApi(context);
        if (minApi == UNKNOWN_API) {
            return;
        }
        var requiredApi = getRequiredApi(owner);
        if (requiredApi == null || requiredApi <= minApi || isGuarded(node, requiredApi)) {
            return;
        }

        var message = "This Xposed API requires API " + requiredApi + " but the module minApiVersion is " +
                minApi + "; wrap this usage in `if (getApiVersion() >= " + requiredApi +
                ")` or raise minApiVersion.";
        context.report(ISSUE, node, context.getLocation(node), message);
    }

    private int getMinApi(JavaContext context) {
        var projectDir = context.getMainProject().getDir().toPath();
        return minApiCache.computeIfAbsent(projectDir, SinceApiDetector::readMinApi);
    }

    private static int readMinApi(Path projectDir) {
        var srcDir = projectDir.resolve("src");
        if (!Files.isDirectory(srcDir)) {
            return UNKNOWN_API;
        }

        var modulePropSuffix = Path.of("META-INF", "xposed", "module.prop");
        var minApi = Integer.MAX_VALUE;
        try (Stream<Path> paths = Files.walk(srcDir, 8)) {
            var iterator = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.endsWith(modulePropSuffix))
                    .iterator();
            while (iterator.hasNext()) {
                var parsed = readMinApiFromModuleProp(iterator.next());
                if (parsed != UNKNOWN_API) {
                    minApi = Math.min(minApi, parsed);
                }
            }
        } catch (IOException ignored) {
            return UNKNOWN_API;
        }
        return minApi == Integer.MAX_VALUE ? UNKNOWN_API : minApi;
    }

    private static int readMinApiFromModuleProp(Path moduleProp) {
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(moduleProp, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return UNKNOWN_API;
        }
        var value = properties.getProperty("minApiVersion");
        if (value == null) {
            return UNKNOWN_API;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return UNKNOWN_API;
        }
    }

    private static Integer getRequiredApi(PsiModifierListOwner owner) {
        if (owner == null) {
            return null;
        }
        var direct = getDirectSinceApi(owner);
        if (direct != null) {
            return direct;
        }

        if (owner instanceof PsiClass psiClass) {
            return getRequiredApi(psiClass.getContainingClass());
        }
        if (owner instanceof PsiMethod method) {
            return getRequiredApi(method.getContainingClass());
        }
        if (owner instanceof PsiField field) {
            return getRequiredApi(field.getContainingClass());
        }
        return null;
    }

    private static Integer getDirectSinceApi(PsiModifierListOwner owner) {
        var annotation = owner.getAnnotation(SINCE_API);
        if (annotation == null) {
            return null;
        }
        return getAnnotationIntValue(annotation);
    }

    private static Integer getAnnotationIntValue(PsiAnnotation annotation) {
        var value = annotation.findAttributeValue("value");
        if (value == null) {
            return null;
        }

        if (value instanceof PsiExpression expression) {
            var evaluated = JavaPsiFacade.getInstance(value.getProject())
                    .getConstantEvaluationHelper()
                    .computeConstantExpression(expression);
            if (evaluated instanceof Number number) {
                return number.intValue();
            }
        }

        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof Number number) {
            return number.intValue();
        }

        var reference = value.getReference();
        if (reference != null && reference.resolve() instanceof PsiField field) {
            var constant = field.computeConstantValue();
            if (constant instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    private static boolean isGuarded(UElement node, int requiredApi) {
        var current = node.getUastParent();
        while (current != null) {
            if (current instanceof UIfExpression ifExpression) {
                if (isWithin(node, ifExpression.getThenExpression()) &&
                        conditionImpliesAtLeast(ifExpression.getCondition(), requiredApi)) {
                    return true;
                }
                if (isWithin(node, ifExpression.getElseExpression()) &&
                        conditionFalseImpliesAtLeast(ifExpression.getCondition(), requiredApi)) {
                    return true;
                }
            }
            current = current.getUastParent();
        }
        return false;
    }

    private static boolean isWithin(UElement node, UElement ancestor) {
        if (ancestor == null) {
            return false;
        }
        var current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getUastParent();
        }
        return false;
    }

    private static boolean conditionImpliesAtLeast(UExpression condition, int requiredApi) {
        if (!(condition instanceof UBinaryExpression binary)) {
            return sourceConditionImpliesAtLeast(condition, requiredApi);
        }

        var operator = binary.getOperator();
        if (operator == UastBinaryOperator.LOGICAL_AND) {
            return conditionImpliesAtLeast(binary.getLeftOperand(), requiredApi) ||
                    conditionImpliesAtLeast(binary.getRightOperand(), requiredApi);
        }
        if (operator == UastBinaryOperator.LOGICAL_OR) {
            return conditionImpliesAtLeast(binary.getLeftOperand(), requiredApi) &&
                    conditionImpliesAtLeast(binary.getRightOperand(), requiredApi);
        }

        var leftApi = isApiVersionExpression(binary.getLeftOperand());
        var rightApi = isApiVersionExpression(binary.getRightOperand());
        var leftConstant = getIntConstant(binary.getLeftOperand());
        var rightConstant = getIntConstant(binary.getRightOperand());

        if (leftApi && rightConstant != null) {
            return comparisonImpliesAtLeast(operator, rightConstant, requiredApi);
        }
        if (rightApi && leftConstant != null) {
            return reversedComparisonImpliesAtLeast(operator, leftConstant, requiredApi);
        }
        return sourceConditionImpliesAtLeast(condition, requiredApi);
    }

    private static boolean conditionFalseImpliesAtLeast(UExpression condition, int requiredApi) {
        if (!(condition instanceof UBinaryExpression binary)) {
            return sourceConditionFalseImpliesAtLeast(condition, requiredApi);
        }

        var operator = binary.getOperator();
        var leftApi = isApiVersionExpression(binary.getLeftOperand());
        var rightApi = isApiVersionExpression(binary.getRightOperand());
        var leftConstant = getIntConstant(binary.getLeftOperand());
        var rightConstant = getIntConstant(binary.getRightOperand());

        if (leftApi && rightConstant != null) {
            return comparisonFalseImpliesAtLeast(operator, rightConstant, requiredApi);
        }
        if (rightApi && leftConstant != null) {
            return reversedComparisonFalseImpliesAtLeast(operator, leftConstant, requiredApi);
        }
        return sourceConditionFalseImpliesAtLeast(condition, requiredApi);
    }

    private static boolean comparisonImpliesAtLeast(UastBinaryOperator operator, int value, int requiredApi) {
        if (operator == UastBinaryOperator.GREATER_OR_EQUALS) {
            return value >= requiredApi;
        }
        if (operator == UastBinaryOperator.GREATER) {
            return value + 1 >= requiredApi;
        }
        if (operator == UastBinaryOperator.EQUALS) {
            return value >= requiredApi;
        }
        return false;
    }

    private static boolean reversedComparisonImpliesAtLeast(UastBinaryOperator operator, int value, int requiredApi) {
        if (operator == UastBinaryOperator.LESS_OR_EQUALS) {
            return value >= requiredApi;
        }
        if (operator == UastBinaryOperator.LESS) {
            return value + 1 >= requiredApi;
        }
        if (operator == UastBinaryOperator.EQUALS) {
            return value >= requiredApi;
        }
        return false;
    }

    private static boolean comparisonFalseImpliesAtLeast(UastBinaryOperator operator, int value, int requiredApi) {
        if (operator == UastBinaryOperator.LESS) {
            return value >= requiredApi;
        }
        if (operator == UastBinaryOperator.LESS_OR_EQUALS) {
            return value + 1 >= requiredApi;
        }
        return false;
    }

    private static boolean reversedComparisonFalseImpliesAtLeast(UastBinaryOperator operator, int value, int requiredApi) {
        if (operator == UastBinaryOperator.GREATER) {
            return value >= requiredApi;
        }
        if (operator == UastBinaryOperator.GREATER_OR_EQUALS) {
            return value + 1 >= requiredApi;
        }
        return false;
    }

    private static boolean isApiVersionExpression(UExpression expression) {
        if (expression instanceof UCallExpression callExpression) {
            var method = callExpression.resolve();
            return method != null && "getApiVersion".equals(method.getName());
        }
        if (expression instanceof UReferenceExpression referenceExpression &&
                "apiVersion".equals(referenceExpression.getResolvedName())) {
            return true;
        }
        if (expression instanceof USimpleNameReferenceExpression referenceExpression) {
            var resolved = referenceExpression.resolve();
            if (resolved instanceof PsiMethod method) {
                return "getApiVersion".equals(method.getName());
            }
            if (resolved instanceof PsiNamedElement namedElement) {
                return "apiVersion".equals(namedElement.getName());
            }
        }
        return false;
    }

    private static Integer getIntConstant(UExpression expression) {
        if (expression instanceof ULiteralExpression literal && literal.getValue() instanceof Number number) {
            return number.intValue();
        }

        if (expression instanceof UReferenceExpression referenceExpression &&
                referenceExpression.resolve() instanceof PsiField field &&
                field.computeConstantValue() instanceof Number number) {
            return number.intValue();
        }

        var sourcePsi = expression.getSourcePsi();
        if (sourcePsi instanceof PsiExpression psiExpression) {
            var evaluated = JavaPsiFacade.getInstance(sourcePsi.getProject())
                    .getConstantEvaluationHelper()
                    .computeConstantExpression(psiExpression);
            if (evaluated instanceof Number number) {
                return number.intValue();
            }
        }
        return getApiConstantFromSource(expression.asSourceString());
    }

    private static boolean sourceConditionImpliesAtLeast(UExpression condition, int requiredApi) {
        var source = condition.asSourceString();
        var matcher = API_AT_LEAST_PATTERN.matcher(source);
        if (matcher.find()) {
            return comparisonImpliesAtLeast(matcher.group(1), getApiValue(matcher.group(2), matcher.group(3)), requiredApi);
        }
        matcher = API_AT_LEAST_REVERSED_PATTERN.matcher(source);
        if (matcher.find()) {
            return reversedComparisonImpliesAtLeast(matcher.group(3), getApiValue(matcher.group(1), matcher.group(2)), requiredApi);
        }
        return false;
    }

    private static boolean sourceConditionFalseImpliesAtLeast(UExpression condition, int requiredApi) {
        var source = condition.asSourceString();
        var matcher = API_BELOW_PATTERN.matcher(source);
        if (matcher.find()) {
            return comparisonFalseImpliesAtLeast(matcher.group(1), getApiValue(matcher.group(2), matcher.group(3)), requiredApi);
        }
        matcher = API_BELOW_REVERSED_PATTERN.matcher(source);
        if (matcher.find()) {
            return reversedComparisonFalseImpliesAtLeast(matcher.group(3), getApiValue(matcher.group(1), matcher.group(2)), requiredApi);
        }
        return false;
    }

    private static boolean comparisonImpliesAtLeast(String operator, int value, int requiredApi) {
        return switch (operator) {
            case ">=" -> value >= requiredApi;
            case ">" -> value + 1 >= requiredApi;
            case "==" -> value >= requiredApi;
            default -> false;
        };
    }

    private static boolean reversedComparisonImpliesAtLeast(String operator, int value, int requiredApi) {
        return switch (operator) {
            case "<=" -> value >= requiredApi;
            case "<" -> value + 1 >= requiredApi;
            case "==" -> value >= requiredApi;
            default -> false;
        };
    }

    private static boolean comparisonFalseImpliesAtLeast(String operator, int value, int requiredApi) {
        return switch (operator) {
            case "<" -> value >= requiredApi;
            case "<=" -> value + 1 >= requiredApi;
            default -> false;
        };
    }

    private static boolean reversedComparisonFalseImpliesAtLeast(String operator, int value, int requiredApi) {
        return switch (operator) {
            case ">" -> value >= requiredApi;
            case ">=" -> value + 1 >= requiredApi;
            default -> false;
        };
    }

    private static Integer getApiConstantFromSource(String source) {
        var matcher = Pattern.compile("\\bAPI_(\\d+)\\b").matcher(source);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static int getApiValue(String value, String apiGroup) {
        if (apiGroup != null) {
            return Integer.parseInt(apiGroup);
        }
        return Integer.parseInt(value);
    }
}
