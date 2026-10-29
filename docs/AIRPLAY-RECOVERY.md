# AirPlay recovery — 1.1.13

## Safari reverse channel

Safari upgrades a POST /reverse connection to PTTH, then acknowledges receiver-generated /event requests with HTTP/1.1 200 OK. The receiver mistakenly called the reverse-mode getter instead of its setter when allocating the next message. The staging prefix still belonged to POST /reverse, so the fallback prefix check also missed the acknowledgement. llhttp then rejected a response as HPE_INVALID_METHOD and closed the reverse channel. Subsequent playlist requests failed even after the first FCUP playlist response had succeeded.

UxPlay patch0024 marks upgraded PTTH connections as reverse before reading acknowledgements. As in the existing design, acknowledgement bytes are drained without request dispatch or body accumulation; media payloads still arrive separately through /action. Completed forward requests reset their staging prefix. The patch retains bounded, ordered reverse-channel output from patch0023.

The loopback regression upgrades a connection, sends fragmented and repeated/coalesced acknowledgements, and requests successive playlist events through the same socket. It fails with four assertions and HPE_INVALID_METHOD before the fix, then passes after it. Existing slow-client, reverse-output ordering, malformed-input and connection stress tests also pass under ASan/UBSan.

## Mirroring lifecycle

The decoder now releases resources even if stop() throws, resets after output-buffer release failure, and invalidates a decoder that reports a fatal error while it is still starting. Creation uses a stable dimension snapshot. Session stop invalidates and wakes first-frame surface waits so a late frame cannot create a decoder after disconnect; a fresh session clears any previous codec.

These address concrete cleanup and cancellation faults found during source inspection. Retained TCL logs show vendor decoder errors around resolution changes, but do not establish that any one of these faults caused the observed intermittent glitches. Android vendor capability-probing warnings alone are not proof of playback failure. Physical sender playback remains necessary to assess the original symptoms.

AirPlayRecoveryTest supplies synthetic single-frame H.264 streams at336×720 and332×720, repeats crop/resolution transitions across sessions, and checks cancellation during the initial surface wait. It does not contact the LAN or use private sender media.

## Monitor deadline

Ordinary service starts no longer postpone an already scheduled keep-alive alarm. A tick schedules the next deadline; a service recreation establishes a new deadline. The retained 198-second late-tick warning is an observation, not proof of TCL process freezing. No alarm, power or firmware policy is overridden.

## Validation status

Native sanitizer suite: passed, including the reproduced Safari regression. Release JVM suite:267 tests passed. Release lint:0 errors,21 warnings. Both signed ABI APKs build with the existing application ID and signing certificate; shared dex/resources/assets match and native provenance includes patch0024.

The debug app and instrumentation APK compile, including the new decoder recovery checks. Emulator execution is currently blocked by T3's stale DEVICE_IN_USE claim; its prescribed stale-release command was refused. No emulator success or physical sender playback result is claimed. This build must complete those emulator checks before TV delivery under the existing workflow.
