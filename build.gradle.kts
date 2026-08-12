import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python") // Python (S7)
}

// Подпись релиза (S12): ключ и пароли — из keystore.properties (в .gitignore, НЕ в репозитории).
// Если файла нет (обычная dev-сборка) — release падает на debug-подпись, чтобы assembleRelease собирался.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "ru.aiagent.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "ru.aiagent.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            // Chaquopy требует явный ABI; Фаза 1 — только arm64 (Q2).
            abiFilters += "arm64-v8a"
        }
    }

    // Python-рантайм (S7): pandas/matplotlib для анализа данных агентом.
    chaquopy {
        defaultConfig {
            version = "3.13"
            pip {
                install("pandas")
                install("matplotlib")
                install("sympy") // вышмат: символьная математика для инструмента cas и run_python (pure-python)
                install("python-pptx") // презентации (.pptx)
                install("python-docx") // документы Word (.docx)
                install("openpyxl")    // продвинутый xlsx (стили/формулы)
                // Топ-пакеты по рекомендации аудита инструментов (Python не изолирован — сеть/ФС доступны,
                // не хватало только библиотек). Все pure-python — собираются без нативных зависимостей.
                install("requests")        // HTTP из Python (сеть работает, не хватало клиента)
                install("beautifulsoup4")  // парсинг HTML (веб-скрейпинг)
                install("jinja2")          // шаблоны (генерация кода/писем/документов)
                install("markdown")        // Markdown → HTML
                install("pyyaml")          // YAML
                install("qrcode")          // генерация QR из Python
                install("pdfplumber")      // извлечение таблиц из PDF (pdf_tables) — pure-python (pdfminer.six)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8: сжатие + обфускация (S12), правила в proguard-rules.pro
            isShrinkResources = true     // выкинуть неиспользуемые ресурсы
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Свой ключ, если задан keystore.properties; иначе debug-подпись (для локального теста).
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    packaging {
        // Хранить .so несжатыми и выровненными по странице (16 КБ на Android 15+).
        jniLibs.useLegacyPackaging = false
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true // транзитивно от :tools-docs
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // JavaMail (android-mail + android-activation) дублируют META-INF/*.
            excludes += setOf(
                "META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-inference"))
    implementation(project(":core-cloud"))
    implementation(project(":core-rag"))
    implementation(project(":integrations"))
    implementation(project(":data"))
    implementation(project(":tools-docs"))
    implementation(project(":voice"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("org.mozilla:rhino:1.7.14")               // JavaScript-движок (run_javascript/repl)
    implementation("org.luaj:luaj-jse:3.0.1")                // Lua-интерпретатор (run_lua)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0") // OCR (rus+eng, офлайн, JitPack)
    implementation("com.sun.mail:android-mail:1.6.7")        // IMAP на устройстве (S10 почта, локально)
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // ключи BYOK (S3)
    implementation("androidx.work:work-runtime-ktx:2.9.1")            // фоновая индексация почты
    implementation("com.google.zxing:core:3.5.3")                    // QR: генерация/сканирование
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // камера-сканер QR (сверка E2E-ключей, Ф3)
    implementation("com.caverock:androidsvg-aar:1.4")                // SVG → растр (векторный рисунок в чат)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")          // PDF-манипуляции (merge/split/delete) без растеризации
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.78.1")       // криптоподпись PDF (pdf_sign, самоподписанный X.509 + PKCS7)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")             // WebSocket-клиент (web_socket) + HTTP
    implementation("org.apache.commons:commons-compress:1.26.2")     // 7z/tar (extract_archive), SevenZFile
    implementation("org.tukaani:xz:1.9")                             // зависимость commons-compress для 7z (LZMA)
    implementation("com.google.mlkit:translate:17.0.3")              // перевод на устройстве (офлайн, бесплатно)
    implementation("com.google.mlkit:language-id:17.0.6")            // определение языка (для translate from:auto)
    // JGit (~3 МБ) вынесен из APK в скачиваемый пак `jgit` (см. packs/jgit/ и GitBackend.kt): d8 от
    // jgit.jar + депы + GitBackendImpl грузится DexClassLoader-ом. compileOnly = ТОЛЬКО для сборки пака
    // (kotlinc GitBackendImpl.kt против этих классов); в сам :app JGit-типы больше не тянутся (работа
    // через тонкий интерфейс GitBackend), поэтому в APK его нет. НЕ менять обратно на implementation.
    compileOnly("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r") { exclude(group = "com.jcraft") } // git clone/commit/push (HTTPS, без SSH)
    // JSch (~280 КБ, mwiede-форк) — ТОЛЬКО для сборки пака `ssh` (packs/ssh/SshBackendImpl.kt против него).
    // В :app SSH-типов нет (работа через тонкий интерфейс SshBackend) → в APK не тянется. НЕ менять на implementation.
    compileOnly("com.github.mwiede:jsch:0.2.21") // SSH-команды на удалённых машинах (ssh_run)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser) // Custom Tabs — страница согласия OAuth поверх приложения
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // реальный org.json в unit-тестах (в android.jar — заглушка)
}
