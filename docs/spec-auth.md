# Auth / startup spec (from 30TV 3.2.3 decompile)
- Endpoint: stored '@endpoint', default https://world.hana-api.com/api/v2. initialize(): action 'test' on current (15s); if fails, race [action 'endpoint' -> result, GET r2.dev/sdk-endpoint.json?t=, GET arvanstorage sdk-endpoint.json] -> [{location,host}], then race 'test' on all hosts, first success wins, persist.
- Headers: c-api-key (the ANDROIDTV platform key, supplied via `namaApiKey` in local.properties), c-token (session), c-version-number 1.0.0, c-language fa|en, user-agent = system UA. Empty headers dropped. 3 tries, 1s apart; after final failure re-run discovery (max once / 30s).
- Envelope {success, result}. 'user' success=false -> logout.
- QR login: qrlogin_code -> result{code,url}; show QR of url; poll qrlogin_login(auth_code=code) every 5s for 60s; success when result.token -> store session. Guest mode allowed.
- user -> result: user_id, user_name, user_type (golden/red/free/blue/staff/admin/robot), user_expire{days}, user_allowed_to_stream, user_avatar.
- Startup: init API, then user + home_stream + continue_watching + menu in parallel, then user_mylist. Retry every 5s on failure.
- Updater: 30tv.txt {versionName, apkUrl, forceUpdate} (not reused: our app is a different package).
- Nav: Home, Search, Menu(categories/genres), My Lists (favorite/watchlist/notify), Settings (user card, days left, sign in/out, version).
- Badge colours: golden #f7ab1b, red #d34040, free #ffffff, blue #4a71b7, staff #6dbe45, admin #68cce3, robot #6fc281.
