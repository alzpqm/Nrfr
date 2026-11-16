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

S25 Android 17 / One UI 9 was observed with the original 1.1.4 installed and
Nightzuku running. The accessible existing logs did not contain a prior
identity-change event; shell APN queries were permission denied. S25 was not
modified for this investigation, and the candidate has not been installed or
validated there. Existing release-signing credentials are unavailable in the
repository CI, so no replacement-signed APK may replace an installed copy.

Raw device output, SIM identifiers, network values, and UI captures are kept
outside Git in the local private diagnostics directory.
