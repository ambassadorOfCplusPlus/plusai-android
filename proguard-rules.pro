# Правила R8/minify для релиза (S12). Библиотеки с рефлексией/JNI нужно защищать явно,
# иначе сжатие переименует/выкинет то, что вызывается по имени из C++/рефлексии.

# --- Общее ---
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
# Нативные методы (JNI) — имена нельзя трогать.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# Enum'ы часто (де)сериализуются по имени.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# --- llama.cpp JNI (генерация/эмбеддинг/зрение) ---
# Класс с external-функциями и колбэк из натива вызываются по имени из C++.
-keep class ru.aiagent.core.inference.LlamaNative { *; }
-keep class ru.aiagent.core.inference.LlamaNative$* { *; }
-keepclassmembers class ru.aiagent.core.inference.** { native <methods>; }

# --- FFmpeg JNI (скачиваемый пак ffmpeg) — обёртка вызывается по имени из libplusffmpeg.so ---
-keep class ru.aiagent.app.code.FfmpegNative { *; }
# --- Whisper JNI (скачиваемый пак whisper) — обёртка вызывается по имени из libpluswhisper.so ---
-keep class ru.aiagent.app.utils.WhisperNative { *; }

# --- JavaMail (IMAP/SMTP на устройстве) — регистрация провайдеров/хендлеров через рефлексию ---
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.ietf.jgss.**

# --- PDFBox-Android (чтение PDF) ---
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# --- BouncyCastle (криптоподпись pdf_sign): JCA-провайдер грузится рефлексией по имени ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# --- OkHttp (web_socket) — тянет javax/conscrypt опционально ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# --- commons-compress (7z/tar в extract_archive) — опциональные кодеки ---
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.**

# --- fastexcel (чтение/запись xlsx) ---
-keep class org.dhatim.fastexcel.** { *; }
-dontwarn org.dhatim.fastexcel.**
-dontwarn org.apache.commons.compress.**

# --- BeanShell (Java-интерпретатор run_java) — исполняет код через рефлексию ---
-keep class bsh.** { *; }
-dontwarn bsh.**
-dontwarn javax.script.** # bsh регистрирует ScriptEngineFactory, но мы зовём bsh.Interpreter напрямую

# --- Rhino (run_javascript/repl) и LuaJ (run_lua) — интерпретаторы на рефлексии ---
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class org.luaj.** { *; }
-dontwarn org.luaj.**

# --- Chaquopy (встроенный Python) — мост Java↔Python через рефлексию/JNI ---
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# --- WorkManager: воркеры инстанцируются по ИМЕНИ класса (рефлексия) — не обфусцировать ---
-keep class ru.aiagent.app.integrations.EmailIndexWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# --- Tesseract4Android (OCR) — JNI обращается к нативным полям/методам по имени ---
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn com.googlecode.leptonica.android.**

# --- Kotlin / корутины ---
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.**
-dontwarn org.slf4j.**

# --- Compile-only ссылки, которых нет в рантайме на Android (R8 missing_rules) ---
# MediaPipe (LiteRT-бэкенд) ссылается на AutoValue-аннотации; StAX (javax.xml.stream) —
# транзитивно от aalto/stax2 в fastexcel; на Android этих классов нет — безопасно глушим.
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.**
-dontwarn javax.xml.stream.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.fasterxml.aalto.**
# JGit (git на устройстве) ссылается на JVM-only классы (ProcessHandle/JMX/slf4j), которых нет на Android.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.slf4j.**
