## Review

- **Medium — The keep-alive is not actually retryable or self-healing in production.** `AccessibilityKeepAliveController.start()` permits retries after `attach()` returns false (`android/app/src/main/kotlin/com/pcontrol/app/AccessibilityKeepAliveSurface.kt:21-24`), but the service calls it only once during connection (`android/app/src/main/kotlin/com/pcontrol/app/BrowserAccessibilityService.kt:93-96`). Moreover, both the controller and surface treat their cached booleans/references as proof of attachment (`AccessibilityKeepAliveSurface.kt:19-24`, `AccessibilityKeepAliveOverlay.kt:23-27`), so a window removed by the platform while the service survives will never be reattached. A transient attach/display failure or platform detach therefore disables the HyperOS keep-alive for the rest of that service lifetime. Schedule a bounded retry after failure and verify `View.isAttachedToWindow` (or receive attach-state callbacks) on interruption/reconnect/display changes. Add tests for initial failure followed by production-triggered retry and for platform detachment while local state still says attached.

- **Medium — Reconnection can orphan the previous keep-alive window.** Every `onServiceConnected()` replaces `keepAliveController` without first stopping an already initialized controller (`android/app/src/main/kotlin/com/pcontrol/app/BrowserAccessibilityService.kt:90-94`), while `onDestroy()` can stop only the latest instance (`BrowserAccessibilityService.kt:393-395`). If the accessibility connection is initialized again on the same service object, the old controller and its attached `WindowManager` view become unreachable, violating the exactly-once cleanup requirement. Tear down/reuse existing keep-alive (and blocking) controllers before reinitialization, and cover repeated connection callbacks with an ownership/attach/detach test.

- **Medium — Service cancellation does not cancel an in-flight sync call.** Although `sync` is declared `suspend`, it performs synchronous `Call.execute()` (`android/app/src/main/kotlin/com/pcontrol/app/SyncClient.kt:99-110`) and catches every `Exception` (`SyncClient.kt:115-116`). Cancelling `TrackerService`'s scope during teardown therefore does not call `Call.cancel()`; the socket and IO worker can survive service destruction until the new 20-second call timeout expires. Make the OkHttp call cancellation-aware (for example, enqueue it through `suspendCancellableCoroutine` and invoke `call.cancel()` on cancellation), and do not consume `CancellationException`. Add a cancellation test that verifies the server-side connection closes promptly. The response-body `use` cleanup itself is correct.

- **Low — The timeout test can pass without exercising a timeout and leaks its server on assertion failure.** `SyncClientTest.kt:22-40` only asserts `null` and an upper time bound, so an implementation that returns `null` immediately without issuing a request passes. `server.shutdown()` is also after the assertions and is skipped on failure. Assert that MockWebServer received the request (and, if retained, a conservative lower bound), and place shutdown in `finally`/`use`.

- **Low — The privacy/input properties of the persistent window are untested.** Production correctly requests a 1x1, non-focusable, non-touchable overlay (`android/app/src/main/kotlin/com/pcontrol/app/AccessibilityKeepAliveOverlay.kt:70-80`), but `AccessibilityKeepAliveControllerTest.kt:8-47` tests only a fake surface and cannot catch a regression that makes the persistent window touchable, focusable, or larger. Extract/test the layout-parameter contract or add an instrumentation/Robolectric adapter test that also verifies detach removes the actual view.

```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "not-satisfied",
      "evidence": "The diff remains Android-focused, but persistent keep-alive retry/reconnection ownership and immediate sync cancellation are incomplete as detailed in the review findings."
    },
    {
      "id": "criterion-2",
      "status": "satisfied",
      "evidence": "Reviewed the actual tracked and untracked Android diff, inspected service/caller lifecycle, ran focused tests with forced execution plus the full Android test task, and verified the index is empty."
    }
  ],
  "changedFiles": [
    "android/app/src/main/kotlin/com/pcontrol/app/AccessibilityKeepAliveOverlay.kt",
    "android/app/src/main/kotlin/com/pcontrol/app/AccessibilityKeepAliveSurface.kt",
    "android/app/src/main/kotlin/com/pcontrol/app/BlockingSurface.kt",
    "android/app/src/main/kotlin/com/pcontrol/app/BrowserAccessibilityService.kt",
    "android/app/src/main/kotlin/com/pcontrol/app/MainActivity.kt",
    "android/app/src/main/kotlin/com/pcontrol/app/SyncClient.kt",
    "android/app/src/test/kotlin/com/pcontrol/app/AccessibilityKeepAliveControllerTest.kt",
    "android/app/src/test/kotlin/com/pcontrol/app/BlockingControllerTest.kt",
    "android/app/src/test/kotlin/com/pcontrol/app/SyncClientTest.kt"
  ],
  "testsAddedOrUpdated": [
    "android/app/src/test/kotlin/com/pcontrol/app/AccessibilityKeepAliveControllerTest.kt",
    "android/app/src/test/kotlin/com/pcontrol/app/BlockingControllerTest.kt",
    "android/app/src/test/kotlin/com/pcontrol/app/SyncClientTest.kt"
  ],
  "commandsRun": [
    {
      "command": "git status --short; git diff --stat; git diff --cached --stat; git diff --check",
      "result": "passed",
      "summary": "Inspected the uncommitted diff and found no whitespace errors."
    },
    {
      "command": "gradle :app:testDebugUnitTest (from repository root)",
      "result": "failed",
      "summary": "Invocation directory was wrong; the repository root is not the Gradle root."
    },
    {
      "command": "cd android && gradle :app:testDebugUnitTest",
      "result": "passed",
      "summary": "App JVM unit-test task completed successfully."
    },
    {
      "command": "cd android && gradle test",
      "result": "passed",
      "summary": "All Android app/core JVM test tasks completed successfully."
    },
    {
      "command": "cd android && gradle :app:testDebugUnitTest --tests 'com.pcontrol.app.AccessibilityKeepAliveControllerTest' --tests 'com.pcontrol.app.SyncClientTest' --tests 'com.pcontrol.app.BlockingControllerTest' --rerun-tasks",
      "result": "passed",
      "summary": "Forced execution: 21 tests passed (13 BlockingController, 6 SyncClient, 2 keep-alive controller), with zero failures/errors."
    },
    {
      "command": "git diff --cached --quiet",
      "result": "passed",
      "summary": "Confirmed no staged files."
    }
  ],
  "validationOutput": [
    "Forced focused test XML: 21 tests, 0 failures, 0 errors.",
    "Full `gradle test`: BUILD SUCCESSFUL.",
    "User-reported Xiaomi HyperOS device validation succeeded for usage, sync heartbeat, and WeChat blocking; not independently rerun during this review."
  ],
  "residualRisks": [
    "A transient or platform-driven keep-alive detach is not recovered during the service lifetime.",
    "Repeated accessibility connection initialization can orphan an attached keep-alive window.",
    "An in-flight synchronous OkHttp call can outlive TrackerService cancellation until its total timeout.",
    "No adapter-level test protects the persistent overlay's non-touchable 1x1 input/privacy contract."
  ],
  "noStagedFiles": true,
  "diffSummary": "Adds persistent 1x1 accessibility keep-alive ownership, foreground reconciliation, MainActivity lifecycle signaling, and an OkHttp total sync timeout/response-body `use` cleanup, with associated JVM tests.",
  "reviewFindings": [
    "medium: AccessibilityKeepAliveSurface.kt:19-24 and BrowserAccessibilityService.kt:93-96 - failed or platform-detached keep-alive is never retried by production",
    "medium: BrowserAccessibilityService.kt:90-94,393-395 - repeated connection initialization can orphan the previous window",
    "medium: SyncClient.kt:99-116 - coroutine/service cancellation does not cancel synchronous OkHttp execution",
    "low: SyncClientTest.kt:22-40 - timeout test is vacuous for an immediate null and lacks guaranteed server cleanup",
    "low: AccessibilityKeepAliveOverlay.kt:70-80 and AccessibilityKeepAliveControllerTest.kt:8-47 - actual non-touchable 1x1 window contract is untested"
  ],
  "manualNotes": "Review-only task; no project/source files were modified."
}
```
