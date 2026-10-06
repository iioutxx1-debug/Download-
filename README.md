# بناء تطبيق صفاء (أندرويد)

المتطلبات: Node.js 18+ و Android Studio (يحتوي Android SDK و JDK).

```
npm install
npx cap add android
npx cap sync android
npx cap open android
```
في Android Studio: Build > Build Bundle(s) / APK(s) > Build APK(s).
الملف الناتج: android/app/build/outputs/apk/debug/app-debug.apk

تعديل الواجهة: عدّل www/index.html ثم نفّذ npx cap sync android.
