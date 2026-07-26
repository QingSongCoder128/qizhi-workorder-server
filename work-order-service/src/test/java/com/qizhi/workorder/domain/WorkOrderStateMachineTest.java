package com.qizhi.workorder.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkOrderStateMachineTest {

    @Test
    void supportsSubmissionApprovalAndRejectionResubmission() {
        assertTrue(WorkOrderStateMachine.canTransition("PENDING_AI", "PENDING_APPROVE"));
        assertTrue(WorkOrderStateMachine.canTransition("PENDING_APPROVE", "APPROVING"));
        assertTrue(WorkOrderStateMachine.canTransition("APPROVING", "REJECTED"));
        assertTrue(WorkOrderStateMachine.canTransition("REJECTED", "PENDING_AI"));
        assertTrue(WorkOrderStateMachine.canTransition("APPROVED", "COMPLETED"));
    }

    @Test
    void protectsTerminalStates() {
        assertFalse(WorkOrderStateMachine.canTransition("COMPLETED", "APPROVING"));
        assertFalse(WorkOrderStateMachine.canTransition("COMPLETED", "COMPLETED"));
        assertFalse(WorkOrderStateMachine.canTransition("CANCELLED", "PENDING_AI"));
        assertThrows(IllegalStateException.class,
                () -> WorkOrderStateMachine.requireTransition("COMPLETED", "REJECTED"));
    }
}
