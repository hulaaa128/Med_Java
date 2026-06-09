package com.echomind.agent;

import com.echomind.intent.IntentCategory;

public record OrchestratorResult(
        String requestId,
        String response,
        AgentType agentType,
        IntentCategory intent,
        boolean escalated,
        long latencyMs
) {
}
