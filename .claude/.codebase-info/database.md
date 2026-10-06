# Persistent State

*Last Updated: 2026-10-06*

There's no ORM and no Room. The app keeps five private stores, all through platform APIs.

| Store | File | Owner | Contents |
|-------|------|-------|----------|
| Default prefs | `com.akylas.enforcedoze_preferences.xml` | `access/Prefs.kt` (new keys), `res/xml/prefs.xml` | Settings, tunables, `executionMode`, `serviceEnabled`, `serviceUserEnabled` (schedule user intent, default true), timing / charging / feature toggles, `customDozePeriods`, `dozeAppBlockList` / `notificationBlockList`, legacy `dozeUsageDataAdvanced` stats |
| Restore ledger | `doze_ledger.xml` | `service/SharedPrefsLedgerStore.kt`, codec in `doze/RestoreLedger.kt` | Keys `restoreLedger`, `restoreLedgerCorruptLines`, `restoreLedgerAnnouncedCorruptLines` |
| Notice state | `notices.xml` | `ui/NoticeSink.java` | `externalBasicRejectedShown`, `externalPrivilegedRejectedShown`, `debtNotified` (StringSet of detail+target) |
| Helper-grant record | `helper_grants.xml` | `access/AccessManager.kt` (+ `HelperGrantPolicy.kt`) | `appliedHelpers` (StringSet of `GrantCommands` keys already applied) |
| Journal | `doze_journal.db` (SQLite v1) | `monitor/JournalDb.kt` | `events` table |

## Preferences

`access/Prefs.kt` holds the new keys and defaults:
- `keepDozeEnforced` = true
- `allowExternalBasicControl` = true
- `allowExternalPrivilegedControl` = false
- `screenOnSummary` = false

`Utils.repairPreferencesPermissions` runs off-main once per process and sets existing pref XML files and their backups to
owner-only.

## Helper-grant record

`AccessManager` applies the `GrantCommands` helpers at SHELL/ROOT, under `helperGrantLock` on a background thread.
- **Triggers.**
  - AUTOMATIC (service start, Main, Doze tunables) skips every key already in `appliedHelpers`, so a helper the user
    later revoked stays revoked.
  - EXPLICIT runs every helper. Its callers are the mode switch and the access card's "grant helpers".
  - `grantHelper(item)` is an EXPLICIT grant of a single helper; the Phone toggle uses it for READ_PHONE_STATE only.
- **Writes.** A key is committed synchronously **before** its grant command runs. A failed commit stops there and returns
  only the prefix that ran; it never throws.
- **Reset.**
  - `SystemReset` calls `AccessManager.forgetHelpers` to forget a helper's key before revoking it, inline or deferred.
  - If forgetting fails, that revoke is skipped and marked FAILED.
  - A successful reset also clears the file in `ResetReport.clearPreferences`.

## Restore ledger

Each entry is one line: `1|FEATURE|target|original|elapsed|attempts|debt|api`.
- `~` encodes null.
- `%`, `|`, LF, CR and `~` are percent-escaped.
- Legacy 7-field lines are still accepted.

Rules:
- Write path: the entry is saved with a synchronous `commit()` **before** the mutation runs. A failed commit means no
  mutation, and only committed intent is cached.
- Corruption: bad lines are kept in `restoreLedgerCorruptLines` and reported separately; an unreadable ledger is never
  overwritten. FORCE_DOZE / MOTION_SENSORS lines are cleared after a verified recovery; the rest stay until the user
  dismisses them (`clearRetainedCorruption`). `restoreLedgerAnnouncedCorruptLines` stops the debt notice repeating.
- Failure accounting: failed restores stay with `attempts` and `debt` set; see architecture.md for when debt is announced.

## Journal schema (`doze_journal.db`, version 1)

```
events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bootId INTEGER NOT NULL, elapsedRealtime INTEGER NOT NULL, wallTime INTEGER NOT NULL, sessionId INTEGER NOT NULL,
  source TEXT NOT NULL,
  type TEXT, historyKind TEXT, deep TEXT, light TEXT, sensor TEXT,
  battery INTEGER, charging INTEGER, detail TEXT,
  historyTruncated INTEGER NOT NULL DEFAULT 0)
INDEX events_session(sessionId), events_wall(wallTime)
```

- Failures: `insert(event, onFailure)` reports a failed write on the journal thread and still fails the Future.
- Decoding is tolerant: an unknown persisted enum name in an optional column (`deep`, `light`, `sensor`) reads as null.
  A row with an unknown `source`, or without exactly one valid `type` / `historyKind`, is skipped, and the skip count is
  logged (`DozeJournal`). Persisted enum names are wire format (pinned by `PersistedNamesTest`), so append values only.
- Retention: each write transaction prunes rows older than 14 days and keeps at most 20,000 rows (`JournalDb.MAX_ROWS`,
  `RETENTION_MS`).
- Migrations: none, since there's no predecessor. An unsupported upgrade throws.
- Threading: all access goes through the `doze-journal` thread, which returns Futures. Monitor reads use their own
  `monitor-reads` / `monitor-journal` threads.

## Backup and sharing

- `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` (cloud and device transfer) exclude
  `doze_journal.db`, `doze_ledger.xml`, `notices.xml` and `helper_grants.xml` (grants are per device). Other prefs keep
  ordinary backup.
- Reports: `monitor/ReportExporter.kt` writes UTF-8 text to `cache/reports/`, the only path in `res/xml/file_paths.xml`.
  It shares through `ACTION_SEND` with a temporary read grant.
