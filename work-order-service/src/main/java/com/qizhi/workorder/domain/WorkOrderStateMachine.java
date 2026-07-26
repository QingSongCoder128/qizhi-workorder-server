package com.qizhi.workorder.domain;

import java.util.Map;
import java.util.Set;

/**
 * Central work-order transition policy. Legacy PENDING_APPROVE is retained as
 * the persisted spelling used by the current production dataset.
 */
public final class WorkOrderStateMachine {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "REVOKED");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "PENDING_AI", Set.of("PENDING_APPROVE", "REJECTED"),
            "PENDING_APPROVE", Set.of("APPROVING", "REJECTED", "COMPLETED", "CANCELLED"),
            "APPROVING", Set.of("APPROVING", "REJECTED", "APPROVED", "COMPLETED"),
            "APPROVED", Set.of("COMPLETED"),
            "REJECTED", Set.of("PENDING_AI", "PENDING_APPROVE")
    );

    private WorkOrderStateMachine() {
    }

    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.equals(to)) {
            return !TERMINAL.contains(from);
        }
        if (TERMINAL.contains(from)) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal work-order transition: " + from + " -> " + to);
        }
    }
}
