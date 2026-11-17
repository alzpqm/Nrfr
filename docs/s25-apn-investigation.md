# APN safety investigation (not a verified S25 repair)

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

Clearing Nrfr data, resetting all APNs and long-term behavior are not verified.
This remains a diagnostic candidate, not a claim that all S25 cases are fixed.

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
