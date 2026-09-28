# Troubleshooting terrain and status

Start with `/lss status` and `/lss diag`. They work without an active request manager and do not start a handshake or download. The status screen is also available from Sodium's options screen. **More** pages through text; **Export** saves a local report. The detailed CLI retains its connection counters, while the summary counts progress from its first sample in the current world. A disconnect or replacement world invalidates the summary immediately.

| Observation | Check next |
| --- | --- |
| Reception OFF | Enable `lod.receive` through Sodium or YAML, save, then run `/lss reload`. OFF preserves stored world data; it is not a cache reset. |
| Awaiting negotiation or handshake send failure | Check that the server has the matching LSS platform artifact and that its startup log shows successful initialization. Preserve the failed-state report before reconnecting. |
| Protocol rejected | Compare the actual client/server versions and compatibility settings. Do not disable compatibility protections as a generic repair. |
| Server explicitly disabled | Ask an operator to inspect `/lsslod diag`, server configuration and any configured service permission gate. The client cannot infer the remote reason. |
| No consumer; integration unknown/unavailable/failed | Check the exact loader, optional-mod versions and route in the [catalog](../compatibility.md). Unknown resolution is distinct from a failed or unsupported integration. |
| Queues or rate events | Compare several timestamped samples. Queue membership alone does not prove overload. Rate events include the composed manual/adaptive local gate, so they do not identify the manual setting as the cause. |
| Far-player renderer unavailable | Check the line/loader renderer capability. A deliberate renderer stub is not an integration failure. |
| Missing terrain with generation disabled | Verify that the requested terrain exists. Disabling generation does not scan or certify the world's coverage. |

Use `/lss diagnostics export` for a client report or `/lsslod diagnostics export` as an operator. The command displays the local JSON path; the adjacent text file is its summary. Reports exclude addresses, player identities, world keys, seeds, aliases, personal paths and raw exception messages. No upload occurs. The full local `/lss diag` and ordinary logs can contain identifying context, so they are not equivalent to the allowlisted export. See [export behavior](status-and-presets.md).

For a setting change, inspect its [apply timing](../reference/settings.md). Sodium Apply saves a draft without activating it; run `/lss reload` to activate saved client settings, or `/lsslod reload` for server settings. If saving fails, recover the retained draft and repair the target file's permissions or disk space before retrying. Server reports distinguish the owner's generation admission gate from the accepted configured value; generation changes apply through reload. Restart is required only for paths explicitly reported as pending. The schema-1 JSON field `generationConfiguredForRestart` retains its historical name for compatibility and now means the accepted configured generation flag. Do not use cache resets, store invalidation, privacy changes or anti-xray changes as an automatic response to a slow download. See [performance diagnosis](performance.md).
