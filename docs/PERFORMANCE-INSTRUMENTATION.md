# Local performance diagnostics

Diagnostics are opt-in. No analytics SDK, uploads, extra app permission, boot
timer or diagnostic thread runs in normal release use. The benchmark harness
lives in `androidTest` and is packaged separately from the release APK.

Enable before starting a new app process:

```sh
adb shell setprop log.tag.MiragePerf DEBUG
adb shell am force-stop tv.mirage.launcher
adb shell am start -n tv.mirage.launcher/tv.glass.launcher.LauncherActivity
```

Force-stop interrupts the receiver too. Do this between playback sessions,
never during a measurement. It retains app data, artwork, ordering and pairings.
For the debug variant, use `tv.mirage.launcher.debug` as the package.

After each run, read the bounded, in-memory recorders:

```sh
adb shell dumpsys activity tv.mirage.launcher/tv.glass.launcher.LauncherActivity mirage-perf
adb shell dumpsys activity service tv.mirage.launcher/io.github.jqssun.airplay.service.AirPlayService mirage-perf
```

`mirage-perf-reset` reads and clears the event ring between runs. Rings retain
at most 256 events per process; overwritten events, missing frame callbacks,
timeouts and canceled operations are reported. A missing measurement is not
a successful zero-duration result. Controlled input trials must cause an observable
focus or view change. No-op keys (for example Back on Home) can produce no frame;
exclude those explicitly rather than treating their timeout as display latency.
Permission prompts and interrupted Home resumes are separate, invalid trials.
Read after a run rather than polling during
each frame. Dump commands use Android's existing shell/system diagnostic access.

## Measurements and limits

| Stage | Evidence | Limit |
|---|---|---|
| Input handling | Key event time, UI receipt, HWUI frame-commit callback | Submission is not physical panel scanout; API 29+ hardware rendering required; FrameMetrics listener is removed after the initial frame |
| Cold usable Home | Visible banner bitmaps, focus, resume state, submitted frame; Android `reportFullyDrawn` | Combine OS launch timing with app markers to include process startup |
| Visible artwork | All visible Home/drawer cells have decoded bitmaps in a submitted frame | Does not wait for offscreen apps or the authoritative package scan when the cached Home is already usable |
| Panel readiness | Laid-out panel, settled animation, appropriate focus, submitted frame | Open/close interruptions are canceled samples |
| Bluetooth connection | Command start through confirmed profile broadcast | Reopening a closed panel only gives an upper bound; failures/timeouts stay separate |
| AirPlay pacing | MediaCodec render timestamps, source PTS interval error, original decoder callback jitter | Output-surface timing, not optical TV timing; GL parking surfaces are unmeasured |
| Mirroring delay | NTP-mapped source PTS to output-surface estimate | Clock synchronization error is unknown; validate with synchronized capture |
| Audio health / A/V sync | Existing audio backlog, underrun, xrun and decode counters | Exact lip-sync still requires a synchronized flash/click audiovisual recording |
| Network recovery | Usable IPv4 network through successful receiver re-advertisement after restart | Local registration is not proof of discovery on a sender; confirm externally |

Successful timings, canceled operations, failures and unavailable measurements
must be reported separately. Use the same sender, content, resolution, settings,
warm-up and workload across at least seven independent runs. Report the per-run
mean/median/range and pooled frame counts; seven samples do not establish a
reliable startup p95. Rendering percentiles need many individual frames.

### Remote PWA timing

Open the paired remote with `?perf=1` while TV diagnostics are enabled. The
normal remote does not download the diagnostic module. Seven clock probes
select the minimum-RTT midpoint estimate and retain its ±RTT/2 uncertainty.
Button timestamps distinguish browser queueing, network/request processing,
server queueing, injection acceptance and TV input/render completion. Requests
do not record typed text, device addresses, PINs, cookies or pairing keys.

Client timing older than one minute is rejected until recalibrated. Match
controlled key sequences by key code and injection/event-time bounds; concurrent
physical/phone inputs or ambiguous matches invalidate the sample. HTTP 202 is
acceptance, not a completed visible action. Report uncertainty on cross-device
latencies. Authenticated diagnostic endpoints exist only while enabled; the
existing origin checks and request limits apply.

### Disable

```sh
adb shell setprop log.tag.MiragePerf INFO
adb shell am force-stop tv.mirage.launcher
adb shell am start -n tv.mirage.launcher/tv.glass.launcher.LauncherActivity
```

The flag is read once per process. Restart both Home and the receiver by
restarting the package. There is no persistent launcher preference to reset.

Measure signed-APK bytes and native payload equality separately from runtime
overhead. On/off overhead comparisons must use the same signed build on the
physical TV with repeated runs. Emulator timings are regression diagnostics,
not the acceptance result for TCL hardware.

## Bounded physical-TV runners

### Cadence attribution (diagnostic candidate 33)

Video snapshots expose `arrival_interval_jitter_us`,
`arrival_vs_pts_error_p95_us`, and `decoded_vs_pts_error_p95_us`, alongside
the existing actual-render interval statistics. Each uses a bounded 120-interval
window. Arrival variation includes sender cadence, network delivery, and native
NTP timestamp conversion; it is not a measurement of network jitter alone.
Compare stages without subtracting their percentiles: different samples and
windows do not make such a subtraction a causal latency measurement.

`late_decoded_outputs` counts frames that reached the presentation clock more
than its late bound behind the selected timeline. `presentation_clock_resyncs`
now counts timestamp discontinuity/overflow handling separately. In candidates
30–32, the latter counter also included late frames, so it must not be interpreted
as proof of repeated clock resets. No presentation scheduling behavior changes
with this diagnostic split.

The two additional stage histories allocate approximately 5.6 KiB of primitive
arrays only when diagnostics are enabled. They record bounded timestamps without
per-frame allocations or logs. JSON and percentile sorting happen on an explicit
diagnostic snapshot. Normal mode creates neither extra history.

Every local collector has a 15-minute hard deadline and bounded repetitions.
Each TV input injector is independently limited to 35 seconds (plus a two-second
kill grace); input commands, diagnostic commands and their outer shell also have
shorter or enclosing deadlines. On this firmware an outer shell timeout alone
does not reliably kill descendants, so it is not sufficient. The worker-level
watchdog was tested with a deliberately idle injector: it exited with timeout
status and no injector remained. Incomplete/low-frame trials are invalid.
