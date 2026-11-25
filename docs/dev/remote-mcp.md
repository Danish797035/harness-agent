# Self-hosted remote CLI/MCP

Scope of the first version: single user, single device, single gateway process. The gateway provides
HTTPS CLI/MCP forwarding, and the mobile phone actively connects to WSS; no mobile phone public
network inbound port or additional VPN is required. For deployment code and English instructions,
see [tools/relay/README.md](../../tools/relay/README.md).

## Deployment and pairing

Prepare domain name, Docker Compose, TCP 80/443 inbound pointing to the server. Do not publish the
gateway HTTP port 8787 directly to the public network.

```sh
cd tools/relay
cp .env.example .env
# edit .env and set MINA_DOMAIN to your own domain only, without scheme or path
docker compose build
docker build --target caddy-check .
docker compose run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose up -d
docker compose cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

View the owner-key file privately on this machine. It is an administrative key, not an MCP token,
and is not populated in the client configuration. Initialization will not overwrite old keys. Caddy
automatically requests a certificate and proxies WebSockets; only Caddy publishes the host port. The
domain name and port must be reachable, and the data and certificate volumes must be retained.

Open the gateway HTTPS address in the browser, enter the management key, and click Create pairing
code. The pairing code is valid for five minutes and can only be used once. Fill in the address and
pairing code in the mobile phone "Settings → Connect to Computer → Remote"; after pairing, you still
need to explicitly turn on "Allow remote access". You must cancel the old pairing before changing
your phone.

### The server already has Nginx

When 80/443 is already used by existing Nginx, use the independent
[compose.nginx.yaml](../../tools/relay/compose.nginx.yaml) instead and do not merge it with the
default Compose file. Configure the same.env and execute it with a fixed project name:

```sh
docker compose -p mina-relay -f compose.nginx.yaml build
docker compose -p mina-relay -f compose.nginx.yaml run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose -p mina-relay -f compose.nginx.yaml up -d
docker compose -p mina-relay -f compose.nginx.yaml cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

The gateway only publishes to server 127.0.0.1:18787; Nginx must be on the same host. Change the
domain name and certificate path in [nginx.conf.example](../../tools/relay/nginx.conf.example) to
actual values and add it to the existing http configuration as an independent virtual host
configuration. The certificate must cover the domain name, and have renewal and smooth loading
arrangements after renewal. First check that there is no conflict between the domain name, map name
and port 18787, then nginx -t, and then reload smoothly; if it fails, only the new configuration
will be withdrawn. The example preserves access logs for WebSocket upgrades, turning off buffering,
proxy retries, and forwarded requests.

All subsequent Compose commands use the same project name and -f parameter. When rolling back,
disable the new virtual host, verify and load Nginx smoothly, and then docker compose -p mina-relay
-f compose.nginx.yaml down; retain the data volume. Do not bind the backend port to the public
network instead, and do not start Caddy with the default Compose file in this deployment.

## Client and unified token

The phone's existing `McpSettings` access token is used for both LAN, remote CLI and MCP. The
gateway does not generate, verify, digest or synchronize the token, it only transparently forwards
`Authorization`, and each request is verified by the mobile phone `RemoteAccess`. When the LAN
switch is turned off, remote connections that have been explicitly opened can still use this token.
USB temporary tokens continue to be used only for loopback entry and not for remote access.

- MCP: Select "MCP → Remote" on your mobile phone, copy the configuration to the client that
supports custom Authorization request header, the address is `https://your-domain/mcp`.
- CLI: Select "CLI / Skill → Remote" on your mobile phone, copy the access token to the computer
  private file, and run the connection command on the page. The command does not carry the token
  text.

```sh
chmod 600 /path/to/token
mina connect --url https://mina.example.com --token-file /path/to/token
mina --url https://mina.example.com --token-file /path/to/token doctor
mina --url https://mina.example.com --token-file /path/to/token task list --limit 5
```

Each remote call must pass in `--url` and `--token-file`; `mina connect` only verifies the
connection and does not save the remote configuration. Both protocols access the same task service
on the mobile phone, and task creation, query, waiting, answering, interrupting and cancellation
behaviors are consistent with the local area network.

### Wake up and unlock via gateway: Off by default

Remote wake and unlock lets the person holding the token enter the lock screen PIN/password on the
phone. In the LAN, only devices on the same network can connect to the mobile phone; through the
gateway, the entire Internet can be connected, and the gateway itself can see the token. Therefore,
by default, requests coming through the gateway cannot see screen capabilities (`mina_device_state`,
`mina_wake_device`, `mina_unlock_device` and `/api/v1/device`, `/api/v1`’s capabilities are not
listed as `device_state`), and tools and endpoints do not appear at all, instead of being rejected
after they appear. To wake up and unlock via the gateway, all three items must be turned on on the
phone: "Allow remote access", "Allow wake up and unlock via gateway" on the remote page (off by
default, stored in the encrypted gateway configuration, return to off when pairing or re-pairing),
and the original "Allow remote screen lighting and unlocking". The behavior of LAN and USB is
unchanged.

After the access token is replaced on the mobile phone, the old tokens on the local area network and
the remote will become invalid immediately, and there is no need to synchronize the gateway; the
client must update the configuration. Unpairing only revokes the device channel and does not replace
the access token; clients holding the token can still access after re-pairing. To disable these
clients, the token should be replaced on the phone.

The gateway does not provide OAuth grant discovery, registration, authorization, or refresh. Clients
that only accept the OAuth flow and cannot configure the Bearer request header are not currently
supported. Browser Origin requests are still rejected by the phone; existing MCP uses stateless JSON
and does not add SSE.

## Schema and Credential Responsibilities

| Credentials | use | Reserve your spot |
| --- | --- | --- |
| Mobile access token | Unified CLI/MCP request authentication | Mobile phone encrypted storage, user-configured client |
| Device channel credentials | Pair a mobile phone to establish WSS and unpair it | Mobile phone encrypted storage, gateway summary |
| Manage keys | Create pairing codes and manage unpairing | Gateway private files, in-process summaries |

`RemoteAccess.handle` Unified authentication and protocol errors, then enter `RemoteEndpoints`;
local monitoring and remote channels share task services. LAN private address check reserved. Remote
requests use non-loopback sources and do not obtain USB permissions. Failure cooling is recorded
separately by caller: the gateway brings the caller address it sees (IPv4 address or /64 prefix of
IPv6) to the mobile phone with the request, and the mobile phone counts according to "gateway +
caller", separate from the locally monitored address. If a caller guesses incorrectly continuously,
it will only cool down itself. During the cooldown period, it will not even release the correct
token, and will not reveal the "correct guess" to it. When the gateway is behind a reverse proxy,
all connections come from the proxy. `-trust-proxy` must be added to let the gateway get the caller
from the last X-Forwarded-For appended by the proxy (only read when the direct peer is a loopback or
private address); two Compose files have been added, and the Nginx example uses `$remote_addr` to
overwrite this header. The gateway's own administrative keys, pairing, and forwarding throttling are
also counted by the same caller. `McpRuntime` provides a mobile unified token for the remote channel
and denies access when remote is turned off; pairing itself does not enable remote.

`RelayGateway` Verifies HTTPS address and pairing response, does not follow redirects. The
`RelayClient` whitelist allows the GET/POST/DELETE of `/mcp`, `/api/v1` and `/api/v1/…`, and
independently retains the query, Authorization, Origin, X-Mina-Client and protocol headers; each
endpoint method, parameter and task permissions are continued to be checked by the mobile phone. The
gateway does not proxy arbitrary URLs or TCP.

`RelaySettings` uses independent Keystore key encryption, atomically saved device configuration, and
the state stream contains only non-secret fields. `RelayNetwork` senses network switching, and
`McpService` follows the foreground service and notification closing actions. App guidance uses
existing components and Chinese, English and Traditional Chinese resources. Switching guidance does
not change the permissions, and the token is read only when copied.

Gateway web UI uses Go templates and embedded native CSS/JS, no frameworks, Node builds or CDNs.
Color matching, font size, spacing, group card and button size map App semantic values to adapt to
light/dark colors and narrow screens. Three dictionaries, English, Simplified Chinese, and
Traditional Chinese, cover the interface, errors, form verification, and confirmation boxes; they
follow the browser by default and can be selected individually. The cookie only saves the language.
Management keys are not backfilled, pairing management does not use browser credential storage, and
submission and confirmation are possible without JS.

## Disconnect and Undo

Close the remote channel and immediately close the channel and keep the pairing; when unpairing,
close it first, then request the gateway to delete the device identity, and keep the closed state
for retry when the network is disconnected. The "Unpairing of mobile phone" in the Web UI must be
confirmed before execution.

The connection is backed off and reestablished, and the operation is not replayed; each request has
an independent number, and repeated numbers for the same connection are not executed. There are a
maximum of eight requests at the same time, the upper limit of the body is 1 MiB, the upper limit of
each query and header is 8 KiB, the timeout is 65 seconds, covering the status wait of up to 50
seconds on the mobile phone. If the mobile phone result is too large, an error will be returned and
the channel will not be cancelled.

`device_offline` means there is no available channel, `delivery_unknown` means it may have been
executed but the result is lost; the task status should be queried and the task will not be
automatically resent, interrupted or unlocked. CLI conservatively reports `outcome_unknown` on HTTP
5xx for write requests. Canceling wait cannot undo the executed action. There is no backlog of
commands offline.

## Verification and operation and maintenance

```sh
cd tools/relay
go test -race ./...
go vet ./...
cd ../..
./gradlew :core:test :app:testDebugUnitTest :core:cliTest :core:relayTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall
```

Cross-language testing launches a real Go gateway, calls the real Python CLI and MCP via a
production Kotlin tunnel and phone authentication, and verifies shared tasks, unified token
rotation, USB token rejection and unpairing. Go is not required for normal JVM testing. The
interface tests are `RelaySectionTest` and `ComputerAccessScreenTest`; the development package is
isolated from the official version through `devInstall`.

`/healthz` only proves that the gateway process is available, but does not mean that the mobile
phone is online. The proxy must preserve Host, WebSocket, and Protocol headers, override
X-Forwarded-For with the caller address, have a timeout greater than 65 seconds, and disable
automatic request retries. By default no body or credentials are logged, and reverse proxies must do
the same. The backup contains the device identity, and restoring the old backup may restore the
revoked device; managing key rotation requires replacing the private file and then restarting,
without changing the mobile phone token.

HTTPS terminates at the gateway/proxy. The gateway can technically read the request header and task
content and must use a trusted gateway; this solution is a transparent forwarding of mobile phone
authentication, not end-to-end encryption from client to mobile phone. PINs/passwords are still only
processed on the phone. Android background restrictions, forced stops, network disconnections, and
restarts will affect availability, and certificates, standby, network switching, and first-time
unlock restrictions must be verified separately.


