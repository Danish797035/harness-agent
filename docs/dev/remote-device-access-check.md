# Remote screen lighting and unlocking verification

Date: 2026-10-31. Based on main commit `65e82c0d01ac0f016db76cde4c76cb9a3c03183c` (0.1.16).

## Scope and layering

- Remote screen opening/unlocking is turned off by default on the mobile phone, and credentials can
  only be configured locally on the computer connection settings page.
- `core/device/DeviceAccess.kt` is responsible for the serial preparation process and actual status
  verification; platform input and identification are provided by Android adaptation.
- `app/settings/DeviceAccessSettings.kt` encrypts the atomic store using a separate Keystore key,
  retains a non-secret state, and does not cache decrypted credentials.
- `McpDeviceTools` provides status, screen lighting, and unlocking interfaces, rejects all
  parameters, and uses the original MCP authentication; it does not enter the model tool directory
  or task history.
- `TaskGate` maintains the original logic and is automatically released after being truly unlocked.
  The main screen device management is independent of the Agent virtual screen.
- The settings page uses existing groups, rows, buttons and input components, corresponding to
  Chinese and English resources; old credentials are not restored or displayed, and the screenshot
  prohibition flag is set when editing.

## Automatic verification

Build environment: JDK 17, Android SDK 36, existing Gradle/AGP/Kotlin versions in the warehouse.

```sh
./gradlew :core:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Final result: 2416 items in core, 977 items in app JVM, no failures or errors; 2 original tests in
core were skipped. Covers dependency direction, document consistency, Chinese and English resources,
original regression and new function testing.

New verification coverage: off by default, no assistant, first unlock boundary, idempotent screen,
command successful but screen not actually lit, no credential lock screen, PIN/password process,
wipe after input, persistent pause after wrong credentials, concurrent calls, cancellation, manual
unlock/revoke permission while waiting, encrypted storage and key loss, credential update
invalidates old requests, MCP parameters/authentication restrictions, original lock screen
self-drawn PIN control and SIM/unknown control exclusion.

## Android 16 test machine

Verified by independent Mina Dev (application identification `com.meenai.mina.dev`, debugging
package with locally changed application identification, not included in this change), the original
release version and its data are retained. The development USB MCP uses a separate port 18765; no
original model keys or other private data are copied.

The user only configures real lock screen credentials on the phone, and the test script does not
read or output the credentials. Records contain only the following non-secret states:

| stage | interactive | keyguard_showing | device_locked | ready |
| --- | --- | --- | --- | --- |
| start | true | false | false | true |
| Turn off and lock the screen | false | true | true | false |
| Remotely brighten the screen and return to awake | true | true | true | false |
| Remote PIN unlock returns unlocked | true | false | false | true |

The persistent occupancy before submission is monitored, and the occupancy is cleared after success,
proving that the credential submission path has actually been followed, instead of only detecting
the unlocked state.

`pinEntry` of the original Android lock screen is self-drawn
`com.android.keyguard.PasswordTextView`, which is not declared editable. The identification rules
are released according to the ID, class name and password flags of the original version. The rest
only recognize edit boxes with password flags and matching input types, and there are regression
tests; coordinate blind spots are not followed, and the control text is not read.

`DeviceAccessSectionTest` passed on the real machine: secondary input confirmation, saving
conditions and input value will not be displayed after saving. The test only uses simulated data
from a separate cache and does not cover real configuration. The first test page did not enter the
foreground. After termination, it was re-run from the development version foreground. The result was
OK (1 test). Remove the test package after verification.

## Explicit unverified parts

- Device verification uses the user's existing PIN; there is no change to the phone's lock screen
  type. Therefore, the passwordless/sliding and password scenarios are covered by automatic testing
  and have not yet been individually switched for verification on this real device.
- Password supports 4–64 printable ASCII characters, PIN is 4–16 digits; pattern, SIM PIN, or
  unknown manufacturer credential page is not supported.
- Launching an existing Mina MCP before first unlocking after reboot is not supported.
- The above real machine record is earlier than the change of "credential keys are only directed to
  the SystemUI window" (`ShellService.injectTo`); after the change, you need to rerun the PIN unlock
  on the real machine, and verify that when there are other windows above the lock screen,
  `input_failed` is returned without input.
- No incorrect PIN was intentionally submitted to the real machine; stopping retries after failure
  is verified by automated testing.
- There are no changes to the original task payment/confirmation strategy, no models configured for
  validation, or external App tasks performed.

Please see [Computer Connection Instructions](../mcp.md) for the steps to use the mobile phone.

