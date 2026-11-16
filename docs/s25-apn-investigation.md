# APN safety investigation (not a verified S25 repair)

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
filter. The current candidate also updates that flag through Samsung's
per-subscription current-operator provider operation and checks the same
filtered view that Settings queries. This later change is **unverified** on
the device. No APN rows are copied.

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
copy was used. The APN editor save transaction and cellular data path remain
unverified.

Disassembly of this S25's installed Samsung Settings shows its APN screen
queries `SIM_APN_URI` with `current = 1` on slot 1 and `current1 = 1` on slot 2.
The installed Samsung TelephonyProvider exposes a per-subscription current
operator operation that updates the corresponding flag. The new candidate
uses this operation and checks that the other SIM's APN count stays unchanged.
It still needs real S25 validation, including APN display, edit/save, selected
APN, rollback, both SIMs, and cellular-bound DNS/HTTPS with Wi-Fi excluded.
