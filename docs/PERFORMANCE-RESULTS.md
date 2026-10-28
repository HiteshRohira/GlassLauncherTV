# Performance measurements — 28 October 2026

Measured on a TCL G06 Google TV, Android 11, ARMv7, 1280×720 at 60 Hz,
approximately 1.4 GiB RAM. These are proposed engineering budgets, not Android
certification thresholds. Rendering results use seven runs of four cycles per
surface with diagnostics disabled. All first-run costs are included.

| Surface | Baseline slow frames | Current slow frames | Current p95 frame time |
|---|---:|---:|---:|
| Home navigation | 0.00% | 0.00% (0/765) | 5 ms |
| Drawer | 1.26% | 0.57% (5/870) | 10 ms |
| Control Center | 4.84% | 0.15% (1/666) | 8 ms |
| Bluetooth panel | 3.43% | 0.55% (5/903) | 7 ms |

The sampled aggregate rendering goals are ≤1% slow frames and p95 ≤16.7 ms.
Mean per-run slow-frame rates are 0%, 0.571%, 0.152%, and 0.571%, respectively.
All current slow frames occurred in the first run; the remaining six were clean.
This is a historical comparison, not a randomized A/B experiment. Android's
vendor-reported GPU overflow buckets are excluded from interpretation.

| Other metric | Measurement | Proposed budget |
|---|---:|---:|
| Process-cold first display, OS caches retained | Mean 536 ms; 507–556 ms; n=7 | ≤1,000 ms |
| Estimated process-cold usable Home | Mean 552 ms; 535–575 ms; n=7 | ≤1,500 ms |
| Visible artwork after first completed frame | Mean 1.49 ms; 1.04–1.90 ms; n=7 | ≤500 ms |
| Combined Home + receiver PSS | 37.9 MiB snapshot | ≤80 MiB |

Usable-Home estimates combine OS first-display timing with additional app readiness
time. Readiness markers describe software frame submission, not physical scanout.
Seven launches do not establish a reliable population p95. Rendering was measured
on development build 32; builds 33 and release 1.1.10 retain the same UI behavior
and add diagnostic attribution/version metadata. Both release ABIs share the same
Java/Kotlin implementation. ARM64 emulator checks are functional validation, not
equivalent physical-hardware performance measurements.

## Remaining limitations

- First post-update drawer readiness reached 394 ms in the initial diagnostic
  pilot, above the 350 ms goal. First post-update display observations were
  approximately 1.59–1.62 seconds, above the 1-second display goal.
- Physical input-to-visible-response latency and recovery after a network
  interruption have not been validated against their budgets.
- Bluetooth reconnection is an integration outcome involving the speaker, radio,
  Android, and launcher. Twenty-eight available-speaker trials averaged 2.87 s;
  sample nearest-rank p95 was 5.24 s versus a proposed 5 s budget. These results
  do not establish a launcher-caused delay.
- AirPlay measurement is deferred. Screen-mirroring trials do not validate direct
  video casting, physical delay, or A/V synchronization. QuickTime local-file
  direct casting showed unsupported content before a video `/play` request reached
  the receiver. No direct-casting quality pass is claimed.

## Implementation

Drawer edge masking avoids a full-grid off-screen layer. Drawer motion uses a
temporary hardware layer. Control Center retains prepared views; tile text changes
only when needed. Bluetooth profile bindings survive panel hiding and status is
cached for Home. Already-soft ambient gradients avoid redundant blur work.

[Performance instrumentation](PERFORMANCE-INSTRUMENTATION.md) is opt-in, bounded,
and disabled in normal operation. It makes no network uploads. Release updates
preserve the application ID, signing identity, preferences, ordering, and pairings.
