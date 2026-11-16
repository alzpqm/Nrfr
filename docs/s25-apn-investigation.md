# APN safety candidate (not a verified S25 repair)

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

The candidate uses a read-only APN type projection permitted by the provider's
legacy read policy; newer provider policy may still deny it. It refuses an
MCC/MNC override when the current APN list cannot be
read, is empty, or the target numeric has no raw APN rows. If the post-change
visible or filtered list becomes empty, it reapplies the previous SIM numeric
and name and checks that the APN list returns. APN provider permission denial
or query failure is an error, never an empty-list result. MCC/MNC modification
remains available for targets with APN rows, subject to the post-change check.
The candidate requests Nightzuku's existing shell APN permission only for an
identity-change operation, and uses it for read-only checks. It checks again
after the CarrierConfig write; a config or verification failure also triggers
identity rollback. The delegated read path has not been run on S25.

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

The candidate has not been installed or validated on S25. Its APN preflight
will fail closed on this device while the provider denies read access, so the
MNC change remains unavailable there until a safe read or verified APN handling
path exists. Existing release-signing credentials are unavailable in the
repository CI, so no replacement-signed APK may replace an installed copy.

Raw device output, SIM identifiers, network values, and UI captures are kept
outside Git in the local private diagnostics directory.

## Side-by-side test after original key loss

The original 1.1.4 signing key has been reported lost. A newly signed APK with
the original `com.github.nrfr` package ID cannot update that installed app.
The `s25Diagnostic` build therefore uses `com.github.nrfr.s25`, has separate
app data, and can be installed beside 1.1.4 without removing it. Its signing
key is local private material outside Git. This build is a diagnostic path,
not an update for existing Nrfr installations or evidence that the APN fault
is fixed. Device validation and any publication decision depend on its actual
S25 results.
