# OptiShare — Google Play Listing Draft

> Draft only. Do not publish claims marked as release-dependent until the corresponding release gate is validated on physical devices.

## App name

**OptiShare**

## Short description — English

**Private, fast local file sharing with multi-file transfer and smart resume.**

## Short description — Arabic

**مشاركة ملفات محلية سريعة وخاصة مع إرسال متعدد واستكمال ذكي للنقل.**

## Full description — English

OptiShare is built for direct, private file sharing between nearby Android devices.

Send photos, videos, music, APK files, documents, archives and other files without uploading your content to an OptiShare cloud server. Select multiple items, connect to a nearby receiver, approve the secure session, and transfer everything in one organized batch.

### Designed for unreliable real-world connections

Large transfer interrupted? OptiShare keeps durable progress checkpoints and can continue from the last confirmed part of the session instead of restarting the whole file from zero.

### Private by design

- No OptiShare account required
- No cloud file relay required
- No advertising SDK in the current release design
- No analytics/tracking SDK in the current release design
- Ephemeral encrypted transfer sessions
- Matching security-code confirmation between the two devices
- SHA-256 verification before received files are published

### Organized automatically

Verified received files are saved under `Download/OptiShare` and organized by type:

- Photos
- Videos
- Music
- Apps/APKs
- Documents
- Archives
- Other

### Multi-file sharing

Build one transfer from many selected files. OptiShare tracks the whole batch while verifying each file independently.

### Local connection

OptiShare uses nearby Android networking such as Wi-Fi Direct for device-to-device transfer. An Internet connection is not required for the file data path.

### Android support

The project targets Android 5.0 and newer. Exact device compatibility is subject to the final tested device matrix before public production.

## Full description — Arabic

OptiShare مصمم لمشاركة الملفات بشكل مباشر وخاص بين أجهزة Android القريبة.

يمكنك إرسال الصور والفيديو والموسيقى وملفات APK والمستندات والملفات المضغوطة وأنواع الملفات الأخرى من دون رفع محتواك إلى خادم سحابي تابع لـOptiShare. اختر عدة ملفات، اتصل بالجهاز المستقبل، أكد جلسة الاتصال الآمنة، ثم أرسل المجموعة كاملة ضمن عملية واحدة منظمة.

### استكمال ذكي عند انقطاع الاتصال

إذا انقطع الاتصال أثناء نقل ملف كبير، يحفظ OptiShare نقاط تقدم مؤكدة ويمكنه المتابعة من آخر جزء تم تثبيته بدلاً من إعادة النقل من البداية.

### الخصوصية أساس التصميم

- لا يحتاج إلى حساب OptiShare
- لا يحتاج إلى خادم سحابي لنقل الملفات
- التصميم الحالي للإصدار لا يحتوي على SDK إعلانات
- التصميم الحالي للإصدار لا يحتوي على SDK تتبع أو تحليلات
- جلسات نقل مشفرة بمفاتيح مؤقتة
- تأكيد رمز أمان متطابق على الجهازين
- التحقق من SHA-256 قبل اعتماد الملف المستلم

### تنظيم تلقائي

يتم حفظ الملفات التي تم التحقق منها ضمن `Download/OptiShare` مع تقسيمها حسب النوع إلى صور وفيديو وموسيقى وتطبيقات/ملفات APK ومستندات وأرشيف وملفات أخرى.

### إرسال عدة ملفات

يمكنك إنشاء عملية إرسال واحدة تحتوي على عدة ملفات. يتابع OptiShare تقدم المجموعة كاملة مع التحقق من كل ملف بشكل مستقل.

### اتصال محلي

يستخدم OptiShare تقنيات Android القريبة مثل Wi-Fi Direct للنقل المباشر بين الجهازين. لا يحتاج مسار نقل الملفات إلى اتصال بالإنترنت.

## Recommended store keywords / concepts

Use naturally in listing copy; do not keyword-stuff:

- local file sharing
- offline file transfer
- Wi-Fi Direct
- private sharing
- resumable transfer
- multi-file transfer
- Android file transfer
- nearby sharing

## Claims that must remain gated until measured

Do **not** publish claims such as these until physical benchmarks prove them:

- “4 MB in under 10 seconds”
- “X MB/s”
- “fastest file-sharing app”
- “works on every Android phone”
- “unbreakable encryption”
- “zero bugs”

## Assets still required

- final launcher/adaptive icon
- 512×512 Play icon
- 1024×500 feature graphic
- phone screenshots of Home, media picker, nearby discovery, secure verification, transfer progress and completion
- Arabic screenshots if Arabic localization ships at launch
- optional tablet/foldable screenshots after layout validation

## Support fields before publication

Replace placeholders only with real production values:

- Support email: **TBD**
- Privacy policy public HTTPS URL: **TBD**
- Website: **TBD**
- Security contact: **TBD**
