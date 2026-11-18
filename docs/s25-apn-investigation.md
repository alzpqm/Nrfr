# S25 APN investigation and bounded device evidence

## User-reported rc1 apply failure: existing target MVNO APNs

The user's subsequent attempt did fail. Its saved failure snapshot showed all
four original APNs still stored, but the requested operator numeric already had
seven APNs. One user-edited IMSI-type MVNO row matched and became the sole
filtered data APN, excluding every original row and the original preferred ID.
This time the requested numeric changed MCC as well; the earlier successful
MNC-only target did not exercise this conflict. Rollback returned the original
numeric and four filtered original rows. The failure is not a permission-denied
empty-list result, nor proof of deletion or an IMSI override.

The installed Samsung provider classifies rows into mutually exclusive buckets:
matched MVNO before MNO, then candidate and carrier-ID handling. Binding an
original MNO to the real carrier ID does not defeat a matching target MVNO.
Removing the equality check would conceal a real data-profile selection error.

The rc2 candidate journals the target rows' original matching metadata before
temporarily assigning each a unique nonmatching ICCID condition. Item IDs,
numeric, connection fields and existing edited states are retained. Both forward
and reverse unique-key checks use the row's current fields. Restoration retains
external connection edits and soft-delete markers, and never recreates deleted
rows. Another active SIM's use of these rows blocks this operation. The same
phone-service matching API used by this provider verifies the condition is false.

Initial rc2 device evidence using the exact failed target and the user's
unchanged default-data SIM: the numeric checkbox was checked, apply completed,
and the four original filtered and Settings IDs were retained. All seven target
rows remained; only mvno_type and mvno_match_data changed. The original preferred
ID and its connection fields stayed unchanged (carrier_id changed as intended).
The other SIM's full original rows/preference stayed unchanged and its bound
DNS/HTTPS 204 passed. Local tests increased to 75 and lint/assembly passed.

After reconnecting, new APN saving passed. Clearing only the candidate's app
data and normally reopening restored all three records exactly, including the
seven target-row entries, without manual import. A second distinct APN saved
and appeared after clear. The initial second fixture reused the first APN's
connection key, correctly failed the collision check without replacing either
row, and passed after editing to a distinct APN value. Pending maintenance
returned to success on that save event.

The exact failed target remained active while SIM 1's subscription-verified
cellular DNS/HTTPS 204 passed. Both temporary rows were deleted by their exact
IDs, never selected. Data disable/enable then produced a new Network handle and
the bound DNS/HTTPS checks passed again. Wi-Fi remained available for ADB and
was excluded from these requests.

Full Restore without reboot returned both SIMs' original numerics, complete raw
APN rows and complete preferred rows exactly to baseline. Every field of all
seven target-numeric rows matched the before-binding snapshot. The exact
inserted carrier rule ID was absent; native rules under this real operator
numeric were retained. All three durable record groups were empty and the
maintenance service stopped. Raw logs, numeric values, SIM identifiers and UI
captures remain local. Both SIMs' three country/name fields, original default
data SIM, screen timeout, USB stay-awake and the original Zhuyin input mode
were restored and verified. The restored default SIM passed bound DNS/HTTPS
204. The installed APK hash matched the signed rc2 artifact. See the rc2 notes
for publication status and limits.

## Wired return: Nightzuku and recovery after clearing app data

The S25 ran Android 17 / One UI 9 and its installed Nightzuku
13.7.1.r53.fce85e4. The candidate now uses the official Nightzuku API and
provider artifacts 13.2.1. Its supported UserService ran as shell UID 2000;
fixed-file write/read/delete, directory mode 0700 and file mode 0600 were
verified on this device. No broker replacement, root or phone reboot was used.
The older RemoteProcess transport returned no useful output in this test;
its cause was not established and it is not used for recovery.

Before an identity or APN metadata write, the candidate atomically stores and
reads back its three recovery records outside the app's clear-data directory,
under a package/certificate namespace. The durable copy is authoritative after
initial migration. Local stale records cannot overwrite a newer durable entry
or resurrect an entry removed during restore. An empty document remains after
cleanup, without any SIM record. Three regression tests cover these cases and
inactive-SIM isolation; all 71 unit tests, lint and diagnostic assembly passed.

SIM 1 was selected with **Modify SIM operator numeric checked**; only MNC
changed and MCC stayed original. The IMSI argument remained null. After
exporting an emergency private backup, only the sidecar's app data was cleared.
Normal reopening restored all three records exactly, **without manual import**.
Both SIMs' full APN rows and preferred rows remained exactly unchanged by clear.
The shell file was readable as UID 2000 and had the verified permissions above.

After reopening, a new APN saved through Samsung Settings appeared on the first
captured return and after settling. Before/after snapshots confirmed the same
row ID and no changes outside numeric, MCC, MNC, carrier ID and the target
slot's current flag. The original preferred row and SIM 2's full rows/preference
were unchanged. Reopening the saved row retained its contents; resaving and
reopening retained them again. The temporary row was then deleted.

While the modified MNC was still active, a Network explicitly requested for
SIM 1 verified its subscription and cellular transport, excluded Wi-Fi, and
passed Network-bound DNS and HTTPS 204. Disabling and re-enabling mobile data
produced a different Network handle and the same checks passed again.

Full Restore returned both SIM numerics, full original APN rows and full
preferred rows exactly to baseline without reboot. The temporary carrier rule
was absent, all three durable record groups were empty, and the maintenance
service stopped. A cold app reopen did not resurrect records. Both SIMs' three
country/name override fields, the original default-data SIM and USB stay-awake
setting were restored; the original data SIM passed bound DNS/HTTPS 204.

An automated text entry was initially converted by Gboard Pinyin; exact input
checks stopped before saving. English input then matched exactly and the
original Pinyin mode was restored. These checks belong to the local test
automation, not a restriction in Nrfr or Samsung Settings.

This supports the published S25 sidecar candidate's tested paths. Clearing
data still requires reopening Nrfr and a working Nightzuku grant before new
APN maintenance. All-APN factory reset, phone reboot persistence, long-term
behavior and other device/build combinations remain unverified. The original
1.1.4 app was retained; raw evidence and signing material remain outside Git.

The sections below describe earlier candidates and their historical failures.

## Second return: the APN save update now passes the tested path

The diagnostic build identified the save failure precisely: Samsung rejects
an item-URI update with a non-null selection or selection arguments. The actual
device implementation has the same explicit check. The repair keeps the item
URI and passes null selection/arguments, with a fresh row check, full unique-key
preflight and complete readback. It does not switch to the collection URI, whose
update path uses REPLACE. These checks do not provide a transaction against
external writers.

With the SIM-numeric checkbox checked and MCC preserved, the new APN appeared
on the first captured return to Settings and remained after settling and
reopening. Before/after private snapshots confirmed the same row ID and
unchanged fields except numeric, MCC, MNC, carrier ID and the selected slot's
current flag. The preferred APN ID stayed unchanged. The other SIM's complete
raw rows and preferred row stayed identical to baseline.

Editing the new APN's name and saving retained the same ID, changed only its
name and kept it visible. Restarting Nrfr preserved the complete rows and
preference without duplicate normalization writes. Deleting the test APN
removed it without recreation. Editing an original APN's name also saved and
stayed visible; its connection fields and preferred ID did not change, and
bound-SIM DNS/HTTPS returned 204.

An automation mistake selected the invalid test APN by clicking its title;
Samsung uses that gesture for selection and the gear for editing. That probe
failed DNS. After selecting an original valid APN, DNS/HTTPS passed. A subsequent
data disconnect/reconnect produced a new Network handle and passed DNS/HTTPS
204 again. The exact original preferred ID was separately read back and its
bound-SIM DNS/HTTPS probe passed. These were cellular Networks with verified
subscription identity, excluding Wi-Fi and the other SIM.

The service now rethrows coroutine cancellation, retains pending failure state
and offers an explicit notification retry. Already-started instrumentation is
allowed to finish releasing delegation before the runner permits another
request. No unbounded retry was added. Assembly, lint and 68 unit tests passed.

### Clearing the diagnostic app's data

Before this test, the sidecar exported its three recovery records to local
private storage and successfully reimported/read them back. The diagnostic-only
transport checks the installed package, signing certificate and current SIM
hashes, rejects different active records and requires the shell's DUMP permission.
It imports neither APN rows nor signing keys.

Clearing only the sidecar's app data preserved both SIMs' full APN and preferred
rows and the modified SIM numeric. Settings still showed two choices with one
selected. Subscription-verified cellular DNS/HTTPS 204 passed after a data
disconnect/reconnect. However, a newly saved APN was hidden again: clearing
Nrfr data removed the maintenance state. Automatic recovery of this case remains
unresolved. The verified private recovery records were reimported, the test APN
deleted, and Restore returned both SIMs' original numeric/full rows/preferred
rows exactly to baseline; the scoped rule was removed and the service stopped.

Resetting all APNs and long-term behavior are not verified. This remains a
diagnostic candidate, not a claim that all S25 cases are fixed.

## 2026-11-17 return: existing APNs preserved; new saves still fail

On the returned S25 (Android 17 / One UI 9), the operator-numeric checkbox was
explicitly checked and only the MNC changed. The IMSI argument stayed null.
Read-only inspection of the device's telephony implementation confirmed that
APN database changes trigger a current-operator rewrite derived from the real
SIM records. Repeatedly writing the synthetic numeric to `current` is not a repair.

A SIM-specific carrier matching rule, scoped by the synthetic numeric and the
full ICCID, retained the real carrier ID. Binding the existing APNs to that
carrier ID in place preserved all four original rows, the Settings choices and
preferred row. Their numeric and connection fields were unchanged. The other
SIM's rows and preference were unchanged. DNS and HTTPS returned successfully
on a Network requested and verified for the selected SIM, including after a
data disconnect/reconnect that produced a new Network handle. Wi-Fi remained
available for ADB but was not used by these probes.

Before the next experiment, restoring the original numeric, APN carrier metadata
and scoped rule returned both SIMs' full original rows and preferred rows exactly
to the saved baseline, without rebooting. A bound-cellular DNS/HTTPS check also
passed after this restore.

New APN saving is still unresolved. Samsung Settings created the test row under
the synthetic numeric, with the target subscription and slot, but its current
flag was zero and it stayed hidden. An experimental foreground observer received
the save event; its subsequent delegated reconciliation failed. Receiving that
event is not evidence of a successful save repair. The experimental build passed
68 unit tests, lint and assembly, which do not replace this failed device check.
No release is justified by these results. Private device identifiers, row IDs,
raw diagnostics, UI captures and signing material remain outside Git.

The sections below retain earlier experiments and their limitations.

The installed 1.1.4 app passes a null IMSI argument to `setCarrierTestOverride`.
This investigation concerns the SIM operator numeric (MCC/MNC), not an IMSI change.

On a rooted dual-SIM OnePlus running Android 14, the default-data SIM had five
raw APN rows and five subscription-visible rows before a test MNC override.
Changing only the MNC to a code with no raw APN rows left the original five
rows in the provider, while the subscription-visible and filtered lists both
became empty. The other SIM retained eight visible and filtered rows. The
preferred APN row remained stored. Restoring the previous numeric returned the
first SIM to five visible and filtered rows, and the preferred row ID matched
the pre-test value. No APN rows were copied, reset, or deleted.

With Wi-Fi disabled, a cellular-interface HTTPS request succeeded before the
override and also after recycling the data connection while the APN list was
empty. Therefore the OnePlus test reproduced APN hiding, but did **not**
reproduce a mobile-data failure. It does not establish the S25 failure cause.

The candidate uses Nightzuku's existing shell APN permission for an identity
change and treats permission denial or query failure as an error, never an
empty list. The first version checked raw, SIM-visible, and filtered counts
and rolled back when those became empty. S25 testing showed these checks were
too broad: Samsung APN Settings applies an additional slot-specific `current`
filter. The next candidate updates that flag through Samsung's
per-subscription current-operator provider operation and checks the same
filtered view that Settings queries. The S25 test below proves this update
is temporary, so this branch is **not safe to release**. No APN rows are copied.

## S25 follow-up on the installed 1.1.4

On Android 17 / One UI 9 with Nightzuku running, both SIMs were active. Before
any identity change, the default-data SIM's APN screen showed two choices and
one selected choice; the other SIM showed one choice and one selected choice.
The SIM numeric matched its subscription numeric. Every shell APN provider
query, including a read-only `type` projection, returned permission denied.
The shell package lists `WRITE_APN_SETTINGS` as granted, but `adb shell content
query` still receives a provider permission denial. This is not an empty APN
list and does not establish whether the delegated app context can read it.

With **Modify SIM operator numeric** checked, 1.1.4 changed only the default
data SIM's MNC. The SIM numeric changed to the requested value while its MCC,
subscription numeric, carrier ID, and network numeric stayed the same. The
other SIM's numeric and carrier ID stayed the same. The system APN Settings log
then recorded zero APNs and TelephonyProvider reported no matching MNO/MVNO
APNs. APN Settings closed even when launched with the target subscription ID,
so adding and saving a new APN could not be tested from that screen. No claim
is made that the APN rows were deleted; the rooted OnePlus test above provides
separate evidence of filtering on that device.

Reapplying the original numeric restored the default-data SIM's APN screen to
two choices with one selected, without clearing or resetting any APN rows. A
separate full-restore check also returned the original numeric after reboot.
That reboot cleared nonpersistent country/name overrides on both SIMs; their
pre-test display settings were reapplied and read back. The other SIM's APN
screen still showed one selected choice. The APN editor's actual save path was
not exercised, because the modified-state APN screen closed.

Before the MNC change, with Wi-Fi disabled, the default-data cellular network
had an INTERNET agent but no VALIDATED capability; cellular-bound HTTPS timed
out during DNS resolution. Android's own NetworkMonitor also recorded DNS
timeouts on that cellular network. This pre-existing failure prevents a claim
that the MNC test caused or repaired mobile-data loss. The modem data call was
connected with no reported setup failure, so the remaining cause needs a
separate cellular-network investigation.

The first sidecar candidate was installed on S25 and read the APN provider
using delegated permission. Rootless shell reads still failed. The diagnostic
candidate has a new signature and cannot update the original package.

Raw device output, SIM identifiers, network values, and UI captures are kept
outside Git in the local private diagnostics directory.

## Side-by-side test after original key loss

The original 1.1.4 signing key has been reported lost. A newly signed APK with
the original `com.github.nrfr` package ID cannot update that installed app.
The `s25Diagnostic` build therefore uses `com.github.nrfr.s25`, has separate
app data, and can be installed beside 1.1.4 without removing it. Its signing
key is local private material outside Git. Its first test with a target MNC
having no raw rows was correctly rejected before the SIM identity changed.
A second target had raw rows and passed the first candidate's APN checks, yet
Samsung APN Settings displayed no APN choices and reported the settings
unavailable. This was a false success. The original SIM numeric was restored;
both SIM subscription identities and APN screens matched the baseline again.
The original 1.1.4 package remains installed. No APN reset, data clear, or row
copy was used in that test.

Disassembly of this S25's installed Samsung Settings shows its APN screen
queries `SIM_APN_URI` with `current = 1` on slot 1 and `current1 = 1` on slot 2.
The installed Samsung TelephonyProvider exposes a per-subscription current
operator operation that updates the corresponding flag. Candidate `c1c89d6`
uses this operation and compares the other SIM's current-row IDs in memory.
CI passed on this commit, and the signed sidecar was installed over the earlier
sidecar without removing 1.1.4.

With SIM 1 selected and **Modify SIM operator numeric checked**, the target
MNC changed while MCC and SIM 2 numeric stayed original. Samsung APN Settings
then showed one selectable target APN but none selected. SIM 2 still showed
one selected APN. A distinct temporary APN was created and saved in Settings;
it appeared in the modified SIM's list. Deleting that temporary APN was logged
as successful by TelephonyProvider. Immediately afterwards, TelephonyProvider
inserted the original operator numeric as current again, and Samsung APN
Settings logged zero matching APNs. A fresh screenshot confirmed the empty
screen. The APN list had therefore only been repaired temporarily by the
candidate's one-time current-operator update. The original APN rows were not
shown to be deleted.

The sidecar's full Restore action returned SIM 1 to its original numeric
**without reboot**. A fresh APN screen showed two choices with one selected;
SIM 2 showed one choice with one selected; the temporary APN was absent. Both
SIM numerics matched the original values. The restore UI requested a reboot
for a separate nonpersistent CarrierConfig clear, but no reboot was needed to
recover the observed SIM numeric and APN screens. Cellular-bound DNS/HTTPS and
an APN selection under the modified MNC were not verified. Earlier cellular
DNS/HTTPS failures predated this candidate, so mobile-data repair cannot be
claimed. The original 1.1.4 and the sidecar remain installed. No public release
has been made.
