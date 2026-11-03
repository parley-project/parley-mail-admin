# Changelog

## parley-mail-admin 2.0.0 (unreleased)

### Changed (2026-11-03: the rest of the deployment pieces come here, To-Do 15.1-15.6)

- **`StoreSetup`** (from parley-mail) is `us.bringardner.parley.mail.admin.server.StoreSetup`, in the server jar, for
  `parley-servers-setup`; `settings.list` lists `parley.mail.admin.**` (from parley-mail's).
- **`ServerContext`** (from parley-net) is `us.bringardner.parley.mail.admin.server.ServerContext`. `SharedUsers`
  gives the launcher's mail servers one list per users file (the mail servers no longer share one by themselves),
  and `MailServices` makes a mail server from its configuration and runs it with its store bean (and the SMTP
  queue's) registered by the launcher's id.
- **The DNS page writes the zone files** (`ZoneFiles`): the shared zone's (records added, changed, deleted: the
  serial raised, the text checked with parley-dns's `SharedZoneText`, the file before kept as `.previous`, refused if
  the file changed meanwhile) and a new domain's `$INHERIT` zone file; then the DNS server reads them again. The
  DNS server no longer writes files.

### Changed (2026-11-03: the mail suite's launcher and providers are the application's)

- **The server side of the application** is `us.bringardner.parley.mail.admin.server`, also published as
  `parley-mail-admin-VERSION-server.jar` (a second jar from this project), which the parley-mail service runs without
  the web application: the launcher (`ServerLauncher`, `ServerProvider`, `LaunchSettings`, the `ParleyServerMBean`
  and `ParleyLauncherMBean` interfaces, from parley-net), its main (`LauncherMain`, parley-net's `ServerMain.main`
  and `launch` until now) and `DnsMain` (DNS alone, managed like the other servers), the providers of every server
  (`SmtpProviders`, `ImapProviders`, `Pop3Providers`, `DnsServerProvider` from their modules, `MailServerProvider`
  from parley-mail) and the suite's FTP servers and access control (`FtpProviders`, `FtpAcl`, `SuiteAcl`,
  `StoreDomains`, from parley-mail). Ids, settings names and JMX names are unchanged. `ServerPackageTest` checks the
  package uses no web classes.
- **One server never stops another**: a server that can't start is FAILED with its error, which is printed, and
  the others start and keep running (`startAll` no longer stops those started and throws); the process exits only
  when no server is running. Before, one server that couldn't start stopped every server in the process.
- The tests run each test class in a JVM of its own (`reuseForks=false`): the application picks Parley's logger
  class before any Parley object logs, which the server tests would otherwise have done first.

### Changed (2026-11-02: To-Do 3.6)

- The mail store's bean is now named by the server's id (parley-mail), so it is one of a server's beans; the Servers
  page still doesn't list it among a server's pages (it is the Users page's Mailboxes).

### Added (2026-11-02: mailbox ACLs, To-Do 3.4)

- **Mailbox ACLs (RFC 4314)**: the Users page's Mailboxes button lists a user's mailboxes with their ACL entries
  and sets or removes an entry (a user, `$group@domain` or `anyone`, positive or negative). Changes go through a
  running mail server's `MailStoreAdminMXBean` (parley-mail), under its locks on the store. API:
  `GET /users/{name}/mailboxes`, `POST /users/{name}/mailboxes/acl` (`mailbox`, `identifier`, `negative`,
  `rights`), `POST /users/{name}/mailboxes/acl/delete`; a domain's administrators may use them for its users.
- **Groups**: the Add and Edit user dialogs set a user's groups, each with its domain (`sales@example.com`), kept
  as FileBasedAcl's `groups=a;b`; `GET /users` lists them and `POST`/`PUT /users` take `groups`.
- **Deleting a user** takes the user out of the other users' mailbox ACLs and the share index; with no mail server
  running it can't, and the answer says so (`X-Parley-Warning`).

### Changed (2026-11-02: 2.0.0's setting names and durations, To-Do 2.19)

- **The Settings page uses 2.0.0's names**: the queue's smart host is `smartHost`, `smartHost.port`, `smartHost.user`,
  `smartHost.password`, `smartHost.auth` and `smartHost.tls` (was `relayHost` with `host:port`, `relayUser` ...);
  `maxAge`, `delayWarning` and `dmarc.reportInterval` (were `maxAgeHours`, `delayWarningHours`,
  `dmarc.reportIntervalHours`); `parley.net.acl.reloadInterval` and `parley.net.acl.hashWait` (were `reloadSeconds`,
  `hashWaitMs`); SMTP's `spam.timeout` (was `spam.timeoutMillis`). A setting still under an old name shows in Other
  settings, and the servers no longer read it.
- **Times are durations, with their unit** (a new `DURATION` setting type, and `DURATIONS` for a list, checked with
  parley-core's `Durations`): `30s`, `5m`, `4h`, `5d` (a number without a unit is milliseconds). The queue's `retry`
  (`1m,5m,15m,30m,1h,2h`; it was minutes), `delayWarning`, `maxAge`, `dmarc.reportInterval`; each SMTP server's
  `timeout`; IMAP's and POP3's `autologout`; FTP's `dataTimeout`; each service's `loginFailureDelay` and
  `loginTimeLimit`; the users file check and the password check wait; and a spam provider's duration settings.
- **The servers' log file is parley-core's `parley.log.file`** in a settings file (was `logFile`), for the Logs page.

### Changed (2026-11-01: parley.servers says which services run, To-Do 2.12)

- **The Settings page refuses a port of 0** (To-Do 2.18): ports are 1 to 65535, since 0 no longer turns a
  service off (Turn off on the Dashboard does); the auto-start setting's description and the settings template
  say `parley.servers` decides which services run.
- **Turn on and Turn off** on each Dashboard service: on adds it to `parley.servers` (in `parley-mail.properties`,
  in the services' order) and starts it; off stops it and takes it out. The launchers read the list again when
  their settings are reloaded, so no restart is needed (a launcher that can't, an older one or one given its list
  on the command line, says to restart it). `POST /services/{id}/on` and `/off`; a service's `enabled` is now
  whether the list has it (all of them when it isn't set). Changing the list marks no service for a restart.
- **A port is only a port**: a service's port defaults to its standard port (the implicit TLS ones too: 465, 993,
  995, 990; they were 0, off), and "0 turns it off" is gone from the Settings page. A listed service with implicit
  TLS and no key store is off, with the reason; Start all skips it. The first start's `parley-mail.properties`
  lists every service, with the implicit TLS ones on high ports (2465, 2993, 2995, 2990).

### Added

- **Spam providers**: on the Settings page's SMTP tab (the SMTP server's own settings), "Check for spam" and a Spam
  provider list made of the providers this installation finds at run time (parley-smtp's `SpamProviders`: Rspamd,
  SpamAssassin and any plugin); choosing one shows what it is and its own settings (shown only while it is chosen, the
  rarely needed ones last), with a Test connection button that asks the scanner with the form's settings, saved or not.
  Beside them the refuse and Junk scores, size and timeout. The Domains page has a Spam provider column and button: a domain
  follows the choice, turns checking off, or uses another provider (kept in the queue's `spam.domains`, applied at
  once). New calls `GET /spam/providers` and `POST /spam/test`. The SMTP server reads its own spam settings when it
  starts, so changing them asks for a restart of the SMTP service. Needs parley-smtp's spam providers.
- **FTP**: the services `ftp` and `ftps` (parley-ftp's servers in the mail suite) are on the Dashboard with the other
  services (state, port, TLS, connections, start, stop, restart), have an FTP tab on the Settings page (`parley.mail.ftp.*`
  and `parley.mail.ftps.*`, in `parley-mail-ftp.properties`), and an FTP source on the Logs page. FTP logs in the mail
  users (`name@domain`) and serves each one's folder in the mail store. Needs parley-ftp.

### Changed

- The SMTP history can be on another file system than the mail: the queue's `historyFileSource` (a setting on the
  Settings page, e.g. `FileProxy` for local disk; it needs the history folder) is used to find the folder, as the queue
  does (`QueueHistory.folder`), so the History page reads the same files. Needs parley-smtp with `historyFileSource`.
- One DKIM key for every domain: the primary domain's key is the shared one (the queue's `dkim.shared`, which the admin
  keeps set to the primary domain while it has a key), and a domain without a key of its own signs with it; with Parley
  DNS it gets the key's record from the shared zone, so it needs no zone file. A key made for another
  domain is its own, and its record goes in that domain's zone. The Domains page shows which key each domain signs
  with, and its DNS records list the shared key's record for a domain without its own.
- Domains, aliases and DKIM keys are used at once, with no restart: after a change on the Domains or Aliases
  page the admin has the running SMTP servers read the queue's domains, aliases file and DKIM keys again
  (parley-smtp's `MailQueueMXBean.reload`, `MailSuite.reloadQueue`) and, for a domain or a DKIM key, the
  Parley DNS server its zones. Those changes no longer mark SMTP as needing a restart; when a server can't use
  one, the change is saved and the page says why.
- Administrators manage their own domain only, unless their domain is the primary one (the first
  administrator's, `parley.mail.admin.primaryDomain`), whose administrators manage every domain. A domain's
  administrator has its domain's users, aliases and DKIM keys, and the queue and history of its mail; the
  other pages and calls are the primary's (403). An administrator whose name has no domain can't log in, and
  the first administrator's name needs a domain.
  The primary domain's administrators may change it to another local domain that has an administrator.
- Each server is alone with its own settings (Parley's server design rule): the SMTP, Authentication, IMAP and
  POP3 tabs have a server picker and edit each server's own prefix (`parley.mail.submission.*` is no longer
  taken for the relay's), and a change marks only the servers that read it as needing a restart.
- New Store and Queue tabs edit the store's and the queue's own settings files, where the servers read them
  (`.parley-store.properties` in the mail root, `queue.properties` in the relay's queue folder, on the mail
  root's file system): the store's sharing and locks; the queue's domains, aliases, postmaster, delivery,
  smart host, history, DKIM signing, ARC sealing and DMARC reports. The Queue tab takes the place of Delivery,
  and takes the queue's settings from the SMTP and Authentication tabs. `parley.mail.sharedStore`,
  `parley.mail.lockDomain` and the `parley.mail.smtp.*` delivery settings are gone. Setting one of them makes
  its file; the Dashboard says when one is missing (the servers refuse to start).
- The host name (Settings > SMTP) is one field: the queue's and each SMTP server's.
- The Domains page writes the queue's `domains` and `dkim.keys`, the Aliases page edits the file the queue's
  `aliases` names (and names its own when the queue names none). Nothing is taken from another setting: no
  domain from the host name, no host name from the machine's.
- The Queue page and the Dashboard read the queue of the first running SMTP server on the relay's queue folder
  (`MailQueue,name=<id>`).
- `parley.mail.admin.jmxPasswordFile`: the JMX password from a file (the parley-servers package's
  `/etc/parley/jmx/admin.password`) when `parley.mail.admin.jmxPassword` isn't set.
- A file in a folder this process may not add files to, but may write (the store's settings in the mail root),
  is written in place.
- The theme picker, who is logged in and Log out are at the right of the top bar, on the page title's line, instead
  of at the bottom of the side bar.
- The servers are parley-net launcher servers managed with JMX (`ParleyServerMBean`, `ParleyLauncherMBean`,
  parley-smtp's `MailQueueMXBean`) instead of servers the application made itself. They run in the application
  (a `ServerLauncher` in its JVM) or, with `parley.mail.admin.jmxUrl` set to one or more JMX service URLs, as
  services of their own that the application manages; it then starts nothing itself and leaves them running when
  it stops. The Dashboard says where they run; a server no launcher runs is "unavailable"; a service turned off
  by its port is "off".
- The settings are the files the servers read, in the settings folder (`parley.mail.admin.settingsDirectory`,
  else parley-net's `ServerMain.confDir()`, /etc/parley or the folder of PARLEY_CONF's file, when it exists, else
  the admin home): `parley-mail.properties`, then `parley-smtp.properties`, `parley-imap.properties` and
  `parley-pop3.properties`. A new setting goes to its protocol's file. The application no longer sets system
  properties; files changed outside the pages are read again. A home made by an earlier build keeps its
  `parley-mail.properties` only when the settings folder is the home.
- The users file is read again by the servers themselves (parley-mail's shared `FileBasedAcl`); `SuiteAcl` is gone.
- The SMTP History page reads the history the SMTP servers write (`<mail root>/.smtp-history`); the application's
  own history listener and `history/` folder are gone, and "Keep SMTP history (days)" is now
  the queue's `historyDays` (30). It and the history's on/off and folder settings are on Settings > Queue (it was on Settings > Admin).
- The Logs page also shows log files: the application's, the servers' (`logFile` in a settings file, with its
  rotated copies) and those `parley.mail.admin.logFiles` names.
- DKIM key files made on the Domains page get the process's usual permissions instead of owner-only ones, so
  servers running as another user of the same group can read them.
- Files rewritten through a temporary file (settings, users, aliases, DKIM keys made again) keep the original's
  permissions and group.
- The Dashboard's connections dialog (client, user, TLS) comes from parley-net's `ParleyServerMBean.getConnectionList()`.

### Added

- The first version: a web application (war) that runs parley-smtp, parley-imap and parley-pop3 in one
  process and manages them from a browser: the services' status, start, stop and restart; domains (several,
  with DKIM keys and DNS records); users and aliases per domain; the SMTP queue and its history; the logs;
  every setting. Needs parley-smtp 2.0.0's queue events and virtual domains.
