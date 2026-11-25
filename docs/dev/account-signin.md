# Log in with account

At the top of "Add Service Provider" is the group "Log in with account · No need to fill in Key":
ChatGPT, xAI / Grok, OpenRouter. Clicking on a family will first pop up the explanation panel (what
will happen, what should be paid attention to), click to start logging in; after successful login,
the details of this family will be opened: account, status, available models (can be refreshed), set
as the main model, log out (only clear the local machine).

"Log in with ChatGPT" remains as is (`core/chatgpt/`, authorization code + PKCE, local callback).
Here are a few other account logins and the clear reasons not to do it. Native callbacks
(`core/oauth/LoopbackCallback.kt`) and PKCE (`core/oauth/Pkce.kt`) are common to ChatGPT and
OpenRouter.

Login, encrypted saving, refreshing, and calling each company's own interface are four separate
steps. Access tokens, refresh tokens, device codes are not entered in logs, diagnostic information
is not entered, and text other than user codes on the interface is not entered. The session JSON and
API Key are placed in the same place (`provider-keys.json`) and use the same encryption set.

## xAI/Grok (device code)

- Read `https://auth.x.ai/.well-known/openid-configuration`. The issuer must be `https://auth.x.ai`,
  the device code and token address must be https and the host is `auth.x.ai`. If it doesn't match,
  it will stop and the request will not be sent to other hosts.
- The scope of the device code (RFC 8628) is `openid profile email offline_access grok-cli:access
  api:access`. The user opens the verification page in the browser, checks the short code, and the
  App polls for token exchange. If the host of the verification page is not in the allowed list
  (`auth.x.ai`, `accounts.x.ai`), the fixed official page will be opened instead.
- The client is the public login entrance of xAI official command line tool (public client_id
  `b1a00492-073a-47ea-816f-4c329264a828`). This is not an application registered by xAI for Mina.
  xAI may restrict this usage, and there is a risk that the account will be restricted. The
  interface clearly states before logging in, and it will not happen unless you click "Continue
  logging in".
- Call xAI’s own Responses: `https://api.x.ai/v1/responses` (`ResponsesModel`) and do not convert it
  to other protocols.
- When the access token is about to expire, use the refresh token to replace it with a new one. Only
  one is being replaced at the same time. When the refresh is rejected and can no longer be used,
  the local token will be cleared and "Login has expired" will be reported.
- The login is successful but the call returns HTTP 403: This indicates that this subscription level
  cannot use this interface (`ModelError.AccountPlanNotAllowed`) and does not try again; both the
  details page and the task ending prompt to use xAI's API Key instead.
- Exiting will only clear the token on this phone. The authorization in the xAI account must be
  disconnected by yourself in the xAI account settings (there is no public logout available).

## OpenRouter (Official OAuth PKCE)

OpenRouter official login method for third-party applications (Document:
https://openrouter.ai/docs/use-cases/oauth-pkce), and what it returns is an ordinary OpenRouter API Key.

- Local callback: `LoopbackCallback` opens the idle port selected by the system on `127.0.0.1`, and
the callback address is written as `http://localhost:<port>/auth/callback` (OpenRouter states that any
local callback port can be used).
- Open
`https://openrouter.ai/auth?callback_url=…&code_challenge=…&code_challenge_method=S256&key_label=Mina&state=…`
  with your system browser. The verification string is newly generated every time, only on this
  machine.
- The callback returns `code` and `state`: If the state does not match, it will return 400, and then
  wait; if it does not include code or state (the user refuses on the authorization page), it will
  be treated as not agreeing.
- `POST https://openrouter.ai/api/v1/auth/keys`, JSON `{code, code_verifier, code_challenge_method:
  "S256"}`, return `key`. 403 means the code or verification string is incorrect, or the code has
  expired (10 minutes), 400 means the verification method does not match. If an error occurs, only
  the status code is left, not the text. After the change is completed, the result page is returned
  to the browser.
- Saving method: Press OpenRouter to save as an OpenAI compatible service provider
  (`https://openrouter.ai/api/v1`) by default. The Key is encrypted by `KeyCipher` and stored in
  `provider-keys.json` like other API Keys; the service provider records `signIn = "openrouter"`
  (`services[].signIn` of `providers.json`), and the details are displayed as account login. The
  authorized OpenRouter and the OpenRouter that the user fills in the Key by themselves are each
  from the same company (the authorized one in the list is written as "OpenRouter (account login)").
  The authorization will not override the Key that the user filled in by himself. Logging in again
  will only update the authorized one. The column model and call are the same as filling in the Key.
- After logging out, only the local Key will be deleted, leaving the service provider, model and
  purpose intact; the interface will prompt you to the OpenRouter Keys page
  (`https://openrouter.ai/settings/keys`) to delete the one named Mina.

## Don't do it

### Claude Subscribe

Anthropic states that OAuth is only for its own applications; third-party products must use the
Console's API Key or cloud vendor credentials. Third-party apps are not allowed to provide Claude.ai
login, nor are they allowed to transfer subscription credits to their own requests. Mina continues
to use Anthropic to fill in the API Key by default.

### MiniMax account

MiniMax's official documentation does not provide OAuth for third-party apps: it does not allow
third parties to register their own client entries, nor does it disclose the client_id for third
parties to use. All the account login methods you can find must be borrowed from clients registered
by other companies. Mina does not do this. The valid method for third-party tools in the MiniMax
document is the API Key of the Token Plan (Coding Plan), and you can use MiniMax to fill in the Key
by default in Mina.

### Google Account (Gemini)

Google does not allow third-party software to borrow the OAuth client of its own tools to access the
backend. Third parties need to use the API Key of AI Studio or Vertex AI. Mina continues to use
Gemini to fill in the API Key by default.


