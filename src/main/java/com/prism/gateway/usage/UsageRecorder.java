package com.prism.gateway.usage;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the request log entry and, for served requests, the monthly usage increment in one
 * transaction, so the usage API (sums over logs) and the budget counter can never disagree.
 */
@Service
public class UsageRecorder {

    /** Statuses that returned a 200 to the caller and therefore count as a served request. */
    public static final Set<String> SERVED = Set.of(RequestStatus.OK, RequestStatus.CACHE_HIT);

    private final RequestLogRepository logs;
    private final UsageStore usage;

    public UsageRecorder(RequestLogRepository logs, UsageStore usage) {
        this.logs = logs;
        this.usage = usage;
    }

    @Transactional
    public void record(RequestLogEntity entry) {
        logs.save(entry);
        boolean billable = entry.getVirtualKey() != null
                && (SERVED.contains(entry.getStatus()) || entry.getCostUsd().signum() > 0);
        if (billable) {
            usage.add(entry.getVirtualKey(), entry.getPromptTokens(), entry.getCompletionTokens(),
                    entry.getCostUsd(), RequestStatus.CACHE_HIT.equals(entry.getStatus()));
        }
    }
}
