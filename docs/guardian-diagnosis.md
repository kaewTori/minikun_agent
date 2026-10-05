# Guardian warning diagnosis

Guardian investigates configured health probes, bounded log tails and backup metadata before publishing an alert. Each finding now includes `cause` and `causeConfidence` alongside evidence and the recommended action. Chat inspection and the incident timeline use the same findings.

- `CONFIRMED` means the cause of the warning is directly observed, such as a missing log file or resource usage entering the configured warning range. It does not identify the process responsible for that usage.
- `UNKNOWN` means the underlying cause remains unverified. TCP failure reasons, recent error excerpts and bounded `Caused by` context are still included as evidence; an unreachable service is not automatically described as stopped.
- Recent log incidents require an `ERROR` or `FATAL` level in an ISO-timestamped event within the last 15 minutes. Words such as `error` or `Exception` inside a `WARN`/`INFO` message do not change its severity. Future timestamps are excluded.
- Log evidence is credential-redacted and excerpted. Stack context stops at the next timestamped event, preventing an unrelated request's cause from being attached. The report does not include the full log tail.
- Notifications include the diagnosis, evidence, next step and observation time. The body is bounded for transport; omitted findings remain available through “ตรวจ homelab ให้หน่อย”. Existing stable-failure threshold, cooldown, quiet hours and recovery behavior remain in place.

No service restart is performed by this diagnosis path. Investigation is limited to the configured read-only sources; it does not prove a complete root cause when those sources lack evidence.

Regression checks cover the observed Tavily HTTP 400/fallback `WARN` events, real temporary log files, secret redaction, stale/future events, stack boundaries, CPU thresholds, macOS memory-pressure semantics, missing logs, delivered alert contents, deduplication and recovery.

## Observed incident, 5 October 2026 (Asia/Bangkok)

The live notification history showed a Guardian WARNING at 12:34:30 with `application: recent_error_count=2`. The runtime application log contained two corresponding events at 12:32:06: Tavily returned HTTP 400 (`Query is invalid.`), then `FailoverSearchProvider` selected its fallback with `reason=SearchExecutionException`. Both events were logged at `WARN` level. The previous substring counter treated `error` in the response body and `Exception` in the fallback reason as application errors.

The history showed a recovery notification at 12:47:33. That indicates the Guardian condition cleared; it does not prove that Tavily accepted a subsequent query. The exact reason the original query was invalid was not established from these log excerpts. A current read-only health check found the configured resources and dependencies UP.

Validation: `./mvnw -q package` completed successfully; the test reports contained 1,318 tests, zero failures/errors and eight skipped tests. The updated JAR was built in the workspace. The running service was not redeployed during this investigation.
