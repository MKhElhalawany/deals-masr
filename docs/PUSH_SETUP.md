# الإشعارات الفورية والتطبيق مقفول (Firebase — مجاني)

لما التطبيق **مفتوح** العروض بتوصل لحظياً عن طريق Supabase Realtime، ومش محتاج أي حاجة منك.
لما التطبيق **مقفول**، أندرويد ما بيسمحش لأي تطبيق يفضل متصل، فالطريقة الوحيدة المضمونة هي Firebase Cloud Messaging (FCM) بتاع Google.
خطة Spark المجانية بتاعة Firebase كافية، ومفيهاش كارت فيزا.

## ليه محتاجين تعمل الخطوة دي بنفسك
لازم مشروع Firebase يتعمل **بحساب Google بتاعك**، لأن Google بتطلب موافقتك على الشروط بنفسك وده مش بيتعمل بالنيابة عنك.
بعد كده كل حاجة بتحصل تلقائياً: تسجيل التطبيق، إعدادات البناء، وتشغيل الإشعارات على السيرفر.

## الخطوات (حوالي 3 دقائق)
1. افتح https://console.firebase.google.com واعمل **Create a project** (أي اسم، مثلاً `deals-masr`). تقدر تقفل Google Analytics.
2. من ⚙️ **Project settings** ← تبويب **Service accounts** ← اضغط **Generate new private key** ← هيتنزل ملف JSON.
3. على GitHub افتح الريبو ← **Settings** ← **Secrets and variables** ← **Actions** ← **New repository secret**:
   - Name: `FIREBASE_SERVICE_ACCOUNT`
   - Secret: افتح ملف الـ JSON وانسخ كل محتواه هنا.
4. خلاص. في خلال 15 دقيقة البناء هيشتغل لوحده، يسجل التطبيق في Firebase، ويطلع نسخة APK جديدة فيها الإشعارات (تحديث على نفس التطبيق).

> ⚠️ ملف الـ JSON ده سر: ما تبعتهوش في الشات وما ترفعهوش في الريبو. مكانه الوحيد GitHub Secrets، والسيرفر بياخده من هناك.
> اللي بيدخل الـ APK هو معرّفات عامة بس (api key / app id)، ودي مش أسرار.

لو حساب الخدمة مش مسموحله يسجل التطبيق، التقرير هيقولك. وقتها سجّل التطبيق يدوي بالـ package `com.dealsmasr.app.debug`، ونزّل `google-services.json`، وحط محتواه في Secret اسمه `FIREBASE_ANDROID_CONFIG`.
