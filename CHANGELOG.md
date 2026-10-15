# DiPlay 0.2.20-api17 (wave 13) — 2026-10-15

Android 4.2.2 (API 17) port of the 63-commit increment between `main` @ `c30d3f0d` (0.2.20 next, already ported by wave 12) and `main` @ `969f8714` (the tagged 0.2.20 release). Wave 13 ports that increment (batches A–H); see the [migration report](DiPlay-main分支增量差异迁移报告-0.2.20.md) for per-feature details, file-level diffs and API 17 guards:

- A call Android routes to the earpiece is moved onto the loudspeaker (#797); music keeps playing when the head unit's audio clock drifts by trimming the playback rate (#788); navigation audio attenuates media instead of stealing focus (#752); each transport gets an independent music buffer (`7d991396`); calls record from `VOICE_RECOGNITION` on call-capable sources that do not clip, and rebuffer/queue-full report events are capped at 5 (#770, #789).
- Ambient lighting settings are redesigned: the 31 car colors become a wrapping swatch grid (single color in fixed mode, several when following music, none while the album cover decides), brightness becomes a 0-6 slider, and a plain-language "What happens" summary says what the lamps do while playing, paused, disconnected or at level 0 (#749). Idle is now a choice — steady glow (default), the car's own lighting after 15 s, or the lowest level — because the lowest raw level switches the lamps fully off on a 2023 Han (#772).
- Head units that are not BYD can start the car hotspot without ADB through the platform tethering call, the "Car hotspot is off" dialog offers a Connect action, and the automatic hotspot switch is offered from Android 8.0, not Android 7 (#748, #789, #775).
- Siri opens on a long press of the voice key only on BYD head units, behind a default-off switch, and one press of the wheel's voice key opens Siri once instead of firing twice (#786, #774).
- A head unit with no Bluetooth adapter is told so when choosing an iPhone and when connecting, and a switch that will not stay on adds that wireless CarPlay cannot work there (#780, #581).
- The diagnostic report now includes media keys and media-key focus changes, what Android lists while waiting for the CH341, the vocoder info of the AirPlay SETUP stream, and the car Bluetooth pause/resume outcome during Wi-Fi Direct scanning (#793, #787, #18).
- The waiting screen follows the in-app day/night mode, Choose iPhone names no iPhone before one is chosen, a default-on switch pauses the car's Wi-Fi searching during a Wi-Fi Direct session (Settings → Connection), a running wired session titles its home card "USB CarPlay", and checking ADB access toasts the result (#768, #778, #724, #742).
- A VERSION reply that follows the previous reply's optional trailer is framed instead of rejected (iOS 27 tablets, 24-byte transfers), and an Auto Wi-Fi Direct plan skips 5 GHz when the chip reports no 5 GHz band instead of asking a 2.4 GHz-only head unit for channel 36 first (#744, #740).
- Skipped on API 17: `BluetoothSuspendLifecycleTest` (main's 333-line lifecycle suite needs controller/Journal scaffolding the branch lacks; the six outcome lines are covered by `BluetoothPauseOutcomeTest` instead).

# DiPlay 0.2.20-api17 — 2026-10-14

Android 4.2.2 (API 17) port of the 18-commit increment between `main` @ `9aa9c549` (0.2.19, already ported) and `main` @ `c30d3f0d`. Wave 12 ports that increment (batches A–H) on top of 0.2.19-api17; see the [migration report](DiPlay-main分支增量差异迁移报告-0.2.19-next.md) for per-feature details, file-level diffs and API 17 guards:

- Wired USB CarPlay no longer re-grabs the driver on every config switch: the same claimed interface is reopened, and after a Save & reconnect the host re-requests CarPlay mode so the iPhone does not stay in USB tethering (#518, #335).
- Wired CarPlay asks for the Android 17 local-network permission (guarded to API 37, literal `37` because `CINNAMON_BUN` does not exist at compileSdk 36) and falls back to the shell `TetheringManager` call when the firmware lists no hotspot start command (#737, #616).
- A Bluetooth failure now says the head unit's Android Bluetooth cannot open connections for apps, so wireless CarPlay is impossible and a USB cable is the fix (#709); a saved hotspot with a blank name no longer renders a `"…"` sentence (#?).
- HEVC falls back to H.264 when no hardware decoder can play it, and an FFmpeg `c2.ffmpeg.*` decoder whose pictures cannot be textured is routed through SurfaceView instead of TextureView (#727).
- DiLink 3 cluster-route conflict is surfaced both in setup and in Settings → Cluster map, and the DiLink 4 route flag is derived from the ADB and legacy routes (#722, #301, #552, #609).
- Calls can play on the loudspeaker of an Android phone/tablet used as the head unit (API 31 `setCommunicationDevice` / legacy `isSpeakerphoneOn`), and the microphone opens silently before downlink audio and restarts on silence (#701, #566).
- The in-car settings menu is grouped by driving goal (connection / display / navigation / vehicle / diagnostics / advanced / more) with a rail on wide panels and a chip row on narrow ones, the chosen category is remembered across rebuilds, and the MFi signing target (Local / USB-CH341) is reachable from Settings → Connection as well as the CarPlay menu (#696).
- The diagnostic report now includes the wheel-key service ring buffer and the last uncaught crash recorded by DiPlay, in addition to the existing on-disk crash log (#662, #463).

# DiPlay 0.2.19-api17 — 2026-10-13

Android 4.2.2 (API 17) port of DiPlay 0.2.19 (`main` @ `9aa9c549`). Wave 11 ports the post-0.2.18 increment (57 commits, 38 non-merge; 99 files, +3481/−455) on top of wave 10; see the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for per-feature details and API 17 guards:

- Wireless CarPlay is refused before it starts when the car radio is really off and DiPlay is not the one holding it, so a single-radio head unit is not pulled off its Wi-Fi for a session that cannot work (#677, #641, #671).
- A failed connection now says what the driver can do about it — reset Wi-Fi, turn Bluetooth on in the head unit's settings, join the car hotspot with Auto-Join, check permissions, or save a report (#673).
- A CarPlay session survives the reversing camera taking over the status bar: the canvas is kept instead of being rebuilt at the taller window (#643, #674).
- Returning to the CarPlay screen shows the last frame until the decoder draws a new one, capped at 30 s with keyframe retries — guarded to API 24+ because it needs `PixelCopy` (#669).
- The in-car settings drawer is rebuilt as a glass shell: a header with a close button, scrolling content and a footer holding Save alone, with Exit application moved to the end of the list away from it (#642).
- The DiLink 4 cluster launch gate is ported as well: CarPlay start is held until this window has taken focus and the cluster route settles, so the first session already carries the cluster stream (#663, #536). The report marked it optional; it is included by request.
- Diagnostics can be fetched on a phone — the QR page offers a report download, and a typed address plus a six-digit code reaches the same page from a computer (#686, #687, #685).
- The head unit now records why its hotspot refused to start: unreadable AP state, no start command in the firmware, or a rejected command (#667).
- Traditional Chinese key coverage is complete again, enforced by a new gate over every user-facing string, and Arabic and Japanese fill their remaining gaps (#691, #682, #681).
- Skipped on API 17: granting USB over ADB when a head unit has no system permission page (#689 — the probe is API 25–28 only); the first-setup BYD navigation-audio default (#656 — DiLink only); `HeadUnitCapabilities`/`ConnectionStateMachine` (not wired into any flow).

See the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for skipped-item reasons; full-release vehicle acceptance is not claimed.

# DiPlay 0.2.18-api17 — 2026-10-12

Android 4.2.2 (API 17) port of DiPlay 0.2.18 (`main` @ `d9881b4a`). Wave 10 ports the post-0.2.18 increment (22 commits) on top of wave 9; see the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for per-feature details and API 17 guards:

- Keep wired sessions running when a USB read finishes as DiPlay stops waiting for it, start wired CarPlay on Android 7.1 head units and when a head-unit USB audio driver holds the iPhone, and skip VPN pre-consent dialogs over approved network ADB (#625, #614, #618).
- Stop discarding music packets after a 10 ms wait, drain queued AAC frames at a stream reset, and log the /feedback position against the received audio (#618, #625).
- Show the real connection stage in every app language, stop with a clear cause when a head unit's Bluetooth cannot reach the iPhone, and find the connected iPhone through sink-role Bluetooth profiles (#624, #590, #585).
- Wheel next/previous on the CarPlay screen without the wheel-key service, Geely/Lynk `geely_fx11` wheel keys and Siri, wheel-key service restore retries with diagnostics, and next/previous to answer or decline with CarPlay call keys (#600, #626, #622, #625).
- Settings backup and restore from a phone browser (zxing 3.5.3), an installer choice for updates, and a shared DiPlayButton for code-built chrome with the picture panel repainted on night changes (#598, #593, #594).
- Experimental, off-by-default options: Harman full screen (guarded to API 19+), square corners, clearer navigation voice over USB, MediaTek low-latency decoding (API 21+ paths), a media-key audio focus opt-out, a cluster speedometer, FCE cluster guidance and BYD SOME/IP windshield HUD gating (#613, #596, #622, #608, #615, #607, #599).
- Skipped on API 17: car phone audio via Telecom self-managed calls (#595 — API 26+); the common `<queries>` block stays commented because targetSdk 28 needs no package-visibility filter.
- Wave 10 ports the post-0.2.18 increment (22 commits, `main` @ `d9881b4a`): wired CarPlay starts
  seconds sooner (a NAKed NCM pipe is dropped after 20 ms, #644), a USB configuration without Apple
  Ethernet asks for CarPlay mode first (#644), the auto-confirm accessibility service now sees the
  system USB dialog under `android.app.Dialog` (#644), the wheel-key service waits out Android's
  crashed-service mark (#644), and 5 GHz companion car hotspots are accepted (#644).
- Calls count their RTP position at the stream's own rate (16 kHz, not 48 kHz, #644), the head-unit
  microphone restarts its first failed read (#644), the audio decoder waits at most 100 ms (#644),
  and call playback stats reach the report again (#644).
- Dialogs have a background on Android 9 and older, the cluster turn card goes 5 s after guidance
  ends, and long reports keep the lines that matter (iAP2 rx thinning, usbmux frame cap) (#644).
- Settings search opens from a button, keeps one editor and its keyboard, and no longer leaks layout
  listeners (#645). The app adds Japanese; the release site stays at seven languages (#646, #631).
- Experimental, off-by-default: a cluster music card on the dashboard map (#660 — temporary or
  permanent square cover; the contributor's vehicle test is unresolved).
- Skipped on this port: the release site's Japanese page; the 28 Japanese strings for features this
  branch has not ported (car phone audio #595, the small-window cluster domain #493).

See the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for skipped-item reasons; full-release vehicle acceptance is not claimed.

# DiPlay 0.2.17-api17 — 2026-10-11

Android 4.2.2 (API 17) port of DiPlay 0.2.17 (`main` @ `4314bbe1`). All eight migration waves are complete; see the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for per-feature details and API 17 guards. Wave 8 ports the 0.2.17 increment (44 commits) on top of 0.2.16-api17:

- Count Opus 16 kHz microphone packets by their own rate and record the iPhone clock; expose CarPlay microphone recording for voice memos (#547, #548).
- Keep the wired session alive across corrupted NCM blocks, retry iAP2 identification against older-iOS rejection messages, explain wired-mode rejection and record settings failures, auto-confirm USB on Android 10+ with BYD accessibility settings, and add a driver-lowerable iPhone USB charging-current control (#575, #583).
- Pause the car-head-unit Wi-Fi scan during Wi-Fi Direct on every Android version, recommend the built-in car hotspot in Settings, and store diagnostic reports in the visible Downloads/DiPlay directory (#570, #576, #541).
- Follow ambient lighting with album-cover colors, hide the BYD incoming-call popup on the CarPlay foreground through accessibility, allow CarPlay remote keys on touch devices and add external HID controller support (#537, #539, #549, #550).
- Infer the DiLink controller generation and add a background update check — WorkManager is downgraded to 2.8.1 to fit the branch AGP 7.4.2 build chain (#531, #488).

- Send the Siri and call microphone through a bundled software Opus encoder, advertise Opus only when it can be encoded, and align echo reference, buffered-audio pacing and audio diagnostics (#468, #483, #421, #484, #499).
- Fix wired NCM receive framing, extend the 16 KiB usbfs limit and the step-down USB read retries down to API 17–25 (#478, #495).
- Refresh hotspot addresses after a first wireless timeout, guard the Android 17 local-network permission, handle missing VPN authorization screens, add WPA3 car-hotspot security, suspend car Bluetooth during sessions and wake the app on iPhone Bluetooth connect (#474, #465, #517, #501, #307, #439).
- Add experimental low-latency decoding and direct video output, an FPS counter, a decoder attempt list with operating-rate hints and bounded video backlog recovery (#496, #456, #347, #417).
- Add Light/Dark/Auto appearance, the DiLink setup wizard, a GitHub update check, inline Settings search, staged-change discard confirmation, the swipe-gesture off choice and Settings layout refinements — rebuilt on the branch View stack instead of Compose (#422, #420, #346, #416, #413, #450, #500, #487, #486, #490, #491, #482).
- Place the dashboard car marker with 1% sliders, follow music with ambient lighting, steer navigation volume from wheel keys, route Platform-21 instruments, format DiLink 3 arrival times, repair ADB boot start and port the side panel (#494, #345, #344, #348, #458, #441, #480).
- Skipped or degraded on API 17: Compose UI (rewritten with Views), side-panel blur (Android 12+), the small-window cluster mode domain (#493) and the Android 17 local-network permission request (no-op guard); minSdk stays 17 and the `-api17` suffix marks the port.

See the [migration guide](DiPlay-main新功能迁移至android-4.2.2指南.md) for skipped-item reasons; full-release vehicle acceptance is not claimed.

# DiPlay 0.2.14 — 2026-10-07

- Group Settings by driver goal with search, adaptive layouts, quick controls and reconnect notices (#369).
- Add an independent Interface size control, preserving CarPlay geometry and following real density/window changes (#378).
- Add a default automatic-connection choice, scheduled day/night mode and launcher return to active CarPlay (#373, #365, #376).
- Improve custom car-button image selection and clarify the main app's source-build instructions (#364, #377, #374).
- Repair the captured USBMUX diagnostic trailer, wake drained Bluetooth receive queues and retry explicitly rejected large USB reads once at 16 KiB; improve stream diagnostics (#362).
- Select compatible video output without window hardware acceleration; use Android video decryption when available and retain a fallback (#362, #332).
- Add default-off experimental Smooth video pacing with explicit latency/picture-adjustment limits (#367).
- Preserve usable hotspot interface addresses and authenticated Bluetooth control on the observed rendered-session handoff fallback (#394, #396).
- Add configurable steering-wheel Siri, bounded microphone-source fallback and optional audio-focus handling (#360, #371, #339).
- Repair BYD call-watcher/audio-status behavior and add opt-in, default-off experimental software call echo cancellation and voice filtering (#370).
- Preserve artwork proportions, correct system-bar-aware split/rotation geometry and recognize the specifically observed 1280×480 DiLink 3 cluster surface (#375, #389, #388).

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) for all 20 contribution links, credits and limitations. Android 9/API 28 remains required. See [validation](docs/VALIDATION.md) for measured checks; complete-release vehicle acceptance is not claimed.

# DiPlay 0.2.13 — 2026-10-06

- Enable the legacy Android 9 Wi-Fi Direct group path with generated credentials and serialized ownership/cleanup; requested frequency remains unverified on Android 9 (#282).
- Prefer current IPv4 hotspot endpoints, preserve scoped IPv6 fallback, and refine Auto channel priorities beside a 5 GHz station without guaranteeing a band (#283, #309).
- Avoid unused Android NSD and USB-service dependencies for wireless startup; retain only rendered sessions on the guarded handoff fallback (#313, #300, #258).
- Add explicit experimental hotspot join Check/Apply/Restore on eligible Android 13+, user-configured 5 GHz hotspots, with complete private recovery state and no automatic mutation (#251).
- Switch wireless to USB within the host activity, verify requested permissions independently, preserve other accessibility services and cancel stale permission work (#268).
- Preserve validated USBMUX payload replies with narrow four-byte trailer recovery (#298).
- Add optional live dock/split-screen areas, square-canvas screen rotation and the selected-decoder square check; add a default-off experimental side panel (#246, #277, #284, #245).
- Preserve DiLink 4 native casting mode, offer pre-connection calibration and apply live cluster picture adjustments (#260, #265).
- Retain a recent dashboard turn card only across wireless replacement within its stale window; add a Smaller map choice (125%) and the checked DiLink 3 full-then-half projection sequence with compensation (#304, #306, #296).
- Restore battery reads when only sys.car.protocol is populated and recover eligible unbound wheel services using already-authorized ADB (#285, #297).
- Add independent default-off experimental DiLink 3 call keys and dashboard calls, with initialized watcher readiness, unique ownership, pristine-safe cancellation and retryable dirty cleanup; target-car acceptance remains requested (#243).
- Correct the observed 24 kHz Siri microphone RTP clock while retaining 48 kHz for telephony/unobserved formats; add bounded, default-off experimental AAC-LC buffered music and single-session renderer ownership (#295, #308).
- Set TCP_NODELAY on the touch event channel; contributor latency observations remain device-specific (#311).
- Improve full-size multi-window home/settings appearance, ambient-setting visibility and shared menu persistence; add main-settings car-button customization (#252, #239, #281, #302).
- Add light waiting/cluster placeholders and a 300 ms cluster fade; the main waiting screen follows CarPlay day/night mode (#305, #317).
- Add Traditional Chinese (Taiwan) as the seventh app language, preserve explicit script selection and correct Simplified Chinese hotspot wording (#314, #286). The release website also gains a Traditional Chinese edition.
- Retain the multilingual website groundwork, add the smooth-wireless guide and make buffered-ownership tests deterministic without runtime/API changes (#240, #310, #312).

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) for all 35 contribution links, credits, experimental settings, compatibility limits and diagnostic export steps. Final exact-release validation is recorded in [VALIDATION](docs/VALIDATION.md). This remains a public preview; no fresh complete-release vehicle test is claimed.

# DiPlay 0.2.12 — 2026-10-04

- Add Existing Wi-Fi / Same LAN wireless CarPlay with scoped IPv4/IPv6 discovery and network-change cleanup (#223).
- Wait for a stable car-hotspot interface and recover bounded wireless attempts when no AirPlay TCP follows StartSession (#229); add observed-state, authorized-ADB hotspot fallback on firmware exposing supported commands (#235).
- Improve Apple USB attach matching and narrowly scoped optional USB-prompt assistance (#170, #224).
- Pause Android 10 station scans during eligible hotspot/P2P sessions, preserving Same LAN, with controller leases and durable retryable restoration (#225).
- Improve split-screen, launcher cards, short-screen preparation and virtual cluster/floating-map geometry (#171, #172, #181).
- Add independent system-bar controls and correct in-session save/cancel and Local/USB-CH341 authentication selection (#191, #194).
- Add system, light-sensor, day and night CarPlay appearance modes, richer custom turn cards, and live main-video picture controls (#178, #193, #211).
- Offer custom integer resolution from 30% to 160%, with shared limits, correct 30%/160% labels and decoder/canvas capability fallback; refresh connection settings on resume (#179, #230, #196).
- Reconcile opt-in DiLink 4 cluster routing/calibration into one decoder owner, retain verified HUD gates, and journal exact stock-map holds and recovery (#213, #187).
- Add DiLink 3 guidance text and projection-display support with committed recovery before mutation, partial-setup compensation and retryable stock restoration (#182).
- Add opt-in wheel map zoom and main-screen joystick while preserving press/release and call behavior; reject stale queued work across phone/screen changes (#214, #231).
- Switch supported dashboard contents live using actual delivery and safely retained paused choices; preserve selection across stream/phone replacement (#232).
- Add a five-second dashboard-song-on-change window with timer invalidation, and retain album art while the next transfer is pending (#215, #228).
- Export reports through Downloads, document picker, app-external or private fallback storage, with explicit View/Share actions (#185, #219).

See [0.2.12 release notes](docs/RELEASE-NOTES-0.2.12.md) for the complete corrections, hardware evidence and issue-reporting steps. This remains a public preview; no fresh end-to-end vehicle test of the complete repaired release is claimed.

# DiPlay 0.2.11 — 2026-10-03

- Add preferred Wi-Fi Direct channel selection for the next connection; Auto remains the default, and manual channel rejection/mismatch reports an error (#175).
- Add a movable custom dashboard turn card with 2% position steps; leave unknown arrows blank and clear expired guidance (#155).
- Offer two-, three- or four-finger settings swipes, keeping three as the default (#156).
- Add opt-in read-only legacy vehicle-data detection while preserving default DiLink 5.0 mode; reject unaccepted/stale probe publication and fix accepted-battery lock ordering (#158, integrated through #173).
- Keep wireless location/vehicle data on its runtime Wi-Fi link and defer parked-video decisions until SETUP/event readiness (#157).
- Add optional automatic car-hotspot startup, off by default, with verified own-package authorization and unified vehicle settings (#164/#173).
- Support Android TV/remote controls while preserving head-unit touchscreen Back and absolute knob X/Y; keep its workflow source-only (#146).
- Retain artists across partial title updates and publish song metadata/artwork only when changed (#161, #162).
- Correct Android 9 audio API use, release failed codecs, reconnect with settled geometry/readiness, and require own-app VPN scope (#168 and local corrections).
- Add one guarded Auto-mode API 29 P2P recovery for the exact reported pre-create Builder error, plus bounded wireless/media/theme/own-app-exit diagnostics without payload recording or automatic uploads.

See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) for requirements, device-test limits and fresh-report guidance. Preferred channel selection does not establish stutter as fixed; Qin Plus, Siri, iOS 15 and day/night reports remain under investigation. Android 9 is still the minimum; Wi-Fi Direct needs Android 10+.

# DiPlay 0.2.10 — 2026-10-03

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
