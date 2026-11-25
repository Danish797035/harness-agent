# Desktop check "Log in with ChatGPT"

Before using the mobile phone, first clear the OpenAI side on the computer (macOS). This check uses
the same set of codes as the App: the login is at
`core/src/main/kotlin/com/meenai/mina/core/chatgpt/`, the model list is `ModelCatalog`, the call is
`ResponsesModel`; the entrance is at `core/src/chatgptCheck/`, and does not enter the App.

## Order

In the repository root directory:

```
./gradlew :core:chatgptSignInCheck
```

Optional parameters:

- `-Ptimeout=<seconds>`: How long to wait for the browser to call back, the default is 300 (5 minutes).
- `-Pmodel=<slug>`: Specify which model to use; if not written, select the first inference model
  from the list (if not, select the first one).
- `-Pkeep`: Save your login when finished (see "Privacy" below).

## what happens

1. Create a directory in `~/.mina-dev/chatgpt-check/` (permission 0700), and record the local
   machine ID and registered client_id in host.json; run it again and use the same registration, the
   same as the App. The first run will register a new client.
2. Turn on callback monitoring on 127.0.0.1, print the authorization address and open it with the
   default browser (if it cannot be opened, copy the address yourself).
3. Log in to the ChatGPT account in the browser and agree to the authorization. The terminal appears
   in sequence: callback received, token exchange, ID token verification,
   `chatgpt.tokens.use.direct` granted, as well as the granted permissions, email address, and token
   validity period.
4. List models (slug and display name).
5. Use a model to send "Reply with the single word: pong" once, and print the answer and dosage.
6. Tool call round trip: the model calls `get_time`, returns the result to it as
   function_call_output, and prints the final answer; it also indicates whether there is encryption
   and playback with the call (store: false).
7. Exchange the refresh token for a new token.
8. Finally, there is a summary: each step is ✓ or ✗, and if there is ✗, the process will exit with
   non-zero value (Gradle will display BUILD FAILED, just look at the summary above).

Common mistakes are adulterous, such as: `subscription_sharing_user_not_eligible` (account package
and region do not allow the quota to be used in other apps), `usage_limit_exceeded` (the quota
reaches the upper limit), `unsupported_capability` (which item in the request will be written),
`access_denied` (cancel on the authorization page), client_id is inconsistent (delete the clientId
in host.json and try again).

If you want to just check whether the startup is normal, you can `-Ptimeout=5`: print the
authorization address, open the browser and time out in 5 seconds.

## privacy

- Access tokens, refresh tokens, authorization codes, and ID tokens are never printed. There is no
  ID token in the authorized address.
- The default token is only in the memory of this run and will be lost when the process exits.
- Only by adding `-Pkeep` will it be written to `~/.mina-dev/chatgpt-check/session.json` (permission
  0600); only the email in it will be used as the login prompt next time it is run. Delete this file
  if it is no longer needed.
- These files are in the home directory, not in the warehouse.


