package com.abhinav.taskflow.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * For "no oracle" tests: two error responses must be indistinguishable to an attacker. The only fields allowed to
 * differ are the ones that differ between ANY two requests: when it happened, its correlation id, and the path.
 */
public final class ProblemBodies {

    private static final Set<String> REQUEST_SPECIFIC = Set.of("timestamp", "correlationId", "instance");

    private ProblemBodies() {}

    public static Map<String, Object> withoutRequestSpecificFields(Map<String, Object> problem) {
        Map<String, Object> copy = new LinkedHashMap<>(problem);
        REQUEST_SPECIFIC.forEach(copy::remove);
        return copy;
    }
}
