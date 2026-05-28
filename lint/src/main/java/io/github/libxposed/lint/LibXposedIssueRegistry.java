package io.github.libxposed.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Registers lint checks shipped with libxposed.
 */
public final class LibXposedIssueRegistry extends IssueRegistry {
    private static final Vendor VENDOR = new Vendor(
            "libxposed",
            "io.github.libxposed",
            "https://github.com/libxposed"
    );

    /**
     * Creates the issue registry used by Android lint.
     */
    public LibXposedIssueRegistry() {
    }

    @NotNull
    @Override
    public List<Issue> getIssues() {
        return List.of(SinceApiDetector.ISSUE, InternalApiDetector.ISSUE);
    }

    @Override
    public Vendor getVendor() {
        return VENDOR;
    }

    @Override
    public int getApi() {
        return ApiKt.CURRENT_API;
    }
}
