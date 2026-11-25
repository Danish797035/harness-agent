# Application access (prototype version)

Settings → App Access Control Mina Standard Mobile Tools by Package Name. New applications and
applications without permissions are allowed by default, including financial applications. AI is
classified according to the main purpose of the application; classification and failure of
classification will not change the permissions. Recommended disabling will only take effect after
the user applies a specific list, and will not automatically disable similar applications installed
in the future. Sharing rules for clones and work data copies with the same package name.

When disabled, standard mobile tools refuse automatic reads and operations and return `app_blocked`.
Changing permissions while the task is running also affects subsequent calls. After receiving
`app_blocked`, the model first re-evaluates the route: continue if there are other applications,
sharing targets or web page paths; when the target application must be used, explain to the user
that the application has been disabled in "Settings > Application Access", and explain that they can
allow it by themselves without ending with the original error code. Answers and error descriptions
follow the language of the user message. When the block list is empty, it will not be rejected due
to missing package names; when the list is not empty and the ownership cannot be confirmed, it will
wait briefly and reread it again. If the target ownership is still not confirmed, it will return
`app_access_unverified`, and the permissions will not be changed; if the permission file is damaged,
it will return `app_access_unavailable`, which can be explicitly reset in the settings; the
authorization sensitive interface will return `app_access_protected`, and it is left to the user to
operate.

When the application access page is opened, it only reads the native application manifest and does
not send the content to the model. When you click "Refresh Application List" and there are still
unclassified applications, first confirm that the application name and package name will be sent to
the configured AI model for risk classification; the content within the application will not be
sent. It is recommended to disable only the financial applications that are still allowed and can be
disabled one by one or all at once; already disabled applications will not reappear.

Page copy follows the interface language. The classification name is provided by the interface, and
there is no Chinese in the core; the classification reason is written in the interface language.
After changing the language, the old reason will remain the same and will be updated the next time
it is reclassified. Manually changed categories will not be overwritten by AI, and the interface
will display them as "User Choice"/"Set by you".

When users actively share the screen of a disabled application, they can choose:

- **Allow screen sharing only**: You can actively share the application screen in the future, but
  automatic reading and operation are still prohibited; it can be revoked on the application access
  page.
- **Allow this application**: Resume normal calls and continue this sharing.
- **Cancel sharing**: No collection, no sharing, no change of permissions.

Active sharing includes pictures and text on the same screen. Confirm the source before collection,
and review the permission after collection and before sending; multiple windows need to cover all
involved applications. Allowing this application will clear the independent sharing permission, and
disabling it again requires re-authorization.

When the list is empty, application access does not do anything extra: no window is checked, and
application access rules are not added to the system prompts. When the list is not empty, the screen
reading uses the window ownership of the screen read; when the window does not change, the
subsequent reading uses the previous verification result (up to 1 second).

When opening an app with `wait` and other tasks, look at the target before waiting: the disabled app
returns `app_blocked` on the spot without waiting for it. Waiting only looks at the occupancy
registration, does not read the screen, and does not go through the window verification.

## prototype boundary

This function only restricts standard mobile tools; shell commands, sandboxes, and computer access
(MCP) are not restricted by this access control. Any Android shell and Linux commands retain
existing capabilities and CommandSteer behavior. Scripts are still gated when called through the
standard phone tool; arbitrary commands themselves and file access with the same UID are not
isolated in this release, so application access rules cannot be claimed to protect against these
path bypasses. The prompt word requires the AI to respect the disabling decision, but not the
shell's forced blocking.

Third-party apps may still launch banned apps. Package name checking prevents standard tools from
following up, cannot undo input submitted to Android, and does not promise zero race conditions
between checking and input. History and content already sent to the model are not deleted
retroactively. Refreshing the identification dependency list when uninstalling and reinstalling an
application does not guarantee identity isolation.

