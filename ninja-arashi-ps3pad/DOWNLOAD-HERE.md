# 🎮 Ninja Arashi PS3 v10 — Control Studio

## 1) PS3 TEST v10

**https://drive.google.com/file/d/1Vr_O0zFN6rL6BOe4CVoY5ZHjq3etvAfT/view**

16,723 bytes · sha256 `8d1b1928eefa6060ada3dd46b62d745fd13bd9c4ee7d241b2dec11f141ee8a99`

## 2) Ninja Arashi PS3 v10

**https://drive.google.com/file/d/1xp_riBLXCL6UldT7DNC57RoVRJV8T4wU/view**

65,306,458 bytes · sha256 `5b077beb1952fe099c716628d34b3ed1dd1c80f8cd2cee436c330d2002bb7621`

اسم الأيقونة: **Ninja Arashi PS3v10** · versionName: `1.4-ps3v10`

## Control Studio الجديد

القائمة مقسمة بوضوح إلى Analog Sticks، Face Buttons، Shoulders/Triggers،
Start/Select/L3/R3، D-pad، وExtra Buttons.

كل سطر فيه:

- **📍 PLACE**: اختار مكان اللمس على الشاشة.
- **CHANGE**: اضغطه، ثم اضغط أي زر في يد PS3 لتربطه.
- **1× إلى 5×**: عدد النقرات التي ينفذها الزر بضغطة واحدة. اضغط الرقم لتغييره.

## إخفاء دوائر اللمس

دوائر الأزرار أصبحت **مخفية افتراضياً** أثناء اللعب. الاختيار محفوظ دائماً:

- `SHOW TARGETS` لإظهارها.
- `HIDE TARGETS` أو `MAKE INVISIBLE` لإخفائها كلياً.
- الدوائر المخفية لا تمنع عمل اليد.

## إصلاح الحركة لليسار

تم حذف الانتظار العام القديم الذي كان يمكن أن يتجاهل إشارة رفع زر LEFT ويترك اللاعب
يمشي بلا توقف. v10 يفصل كل إشارة ضغط/رفع، ويقبل إشارة الرفع من القارئ المباشر حتى لو
لم تصل من Unity. رفع LEFT يرفع اللمسة فوراً الآن.

كل الأماكن، الربط، عدد النقرات، وحالة الإظهار/الإخفاء تُحفظ تلقائياً.
