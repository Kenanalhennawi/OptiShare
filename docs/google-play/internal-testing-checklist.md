# OptiShare 2.2 Google Play internal testing checklist

## Before creating the app

- [ ] Developer account identity verification is approved.
- [ ] Create app as `OptiShare`, default language English, app/game = App, free.
- [ ] Package name exactly matches the release AAB; never create a second package accidentally.
- [ ] Confirm Play App Signing enrollment choices before accepting them.
- [ ] Preserve the existing upload/signing certificate continuity; never generate or substitute a key during CI.

## Store and policy setup

- [ ] Publish `privacy-policy.html` at a stable public HTTPS URL and enter it in App content.
- [ ] Add English and Arabic store listing text from `store-listing.md`.
- [ ] Upload app icon, feature graphic, phone screenshots, and any required tablet screenshots.
- [ ] Complete Data safety using `data-safety.md`; do not claim that every route is encrypted in transit yet.
- [ ] Complete App access: no login or special access required.
- [ ] Complete Ads: app contains no ads.
- [ ] Complete Target audience and content, Content rating, and any current Play policy declarations.

## Release validation

- [ ] Use only the AAB produced by the green `build-v22.yml` run.
- [ ] Confirm release version name/code are higher than every previously uploaded build.
- [ ] Confirm CI reports unit tests, lintDebug, lintRelease, APK/AAB build, and signer verification green.
- [ ] Compare the reported signer SHA-256 with the pinned certificate in CI.
- [ ] Install the generated APK on at least two supported Android versions and run a smoke test.
- [ ] Test Android↔Android, Android↔Windows, multi-file queue, pause/resume, retry, duplicate names, and SHA-256 completion.
- [ ] Confirm no test server address, debug logs containing content, or secret material is packaged.

## Testing tracks

- [ ] Start with Internal testing for owner/device smoke testing.
- [ ] Then create the testing track required by Play Console for production access.
- [ ] Follow the tester count and continuous-duration requirement shown in the account's Production access page; Google may change these rules.
- [ ] Keep tester feedback and fix blocking crashes/ANRs before requesting production access.
- [ ] Do not promote to Production until the release candidate passes the real-device stress matrix.

## Post-upload checks

- [ ] Review Play pre-launch report, Android vitals, accessibility findings, and device compatibility exclusions.
- [ ] Download the Play-generated APK set to a test device and verify discovery and transfers.
- [ ] Confirm store support email is `optishare20@gmail.com` and monitored.
- [ ] Record the Play release version, AAB SHA-256, workflow run ID, and commit SHA in the release notes.
