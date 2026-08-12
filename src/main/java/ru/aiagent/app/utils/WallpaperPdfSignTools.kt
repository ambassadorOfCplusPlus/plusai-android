package ru.aiagent.app.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.json.JSONObject
import ru.aiagent.core.agent.AgentTool
import ru.aiagent.core.agent.DangerLevel
import ru.aiagent.core.agent.ToolResult
import ru.aiagent.core.agent.tools.escapeJson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Инструменты рабочего стола и подписи документов (§3.7, §3.11):
 *  - set_wallpaper — установить обои из картинки песочницы;
 *  - pdf_sign      — поставить на PDF настоящую криптографическую подпись.
 *
 * pdf_sign (криптография): в проекте подключён BouncyCastle (bcpkix-jdk15to18 + транзитивный
 * bcprov), поэтому подпись НАСТОЯЩАЯ, а не визуальный штамп. На лету генерируется самоподписанный
 * X.509-сертификат (RSA-2048, SHA256withRSA, срок ~10 лет) — одна личность пользователя,
 * закэшированная в filesDir как PKCS#12. PDF подписывается по стандарту PDFBox через
 * SignatureInterface: словарь PDSignature (adbe.pkcs7.detached) + detached-контейнер PKCS#7
 * (CMS SignedData) с сертификатом и подписью, дописанный в документ инкрементально (saveIncremental).
 */

// -------------------------- set_wallpaper --------------------------

/**
 * set_wallpaper — установить картинку из песочницы как обои. target: home (рабочий стол) /
 * lock (экран блокировки) / both. Требует обычного (не runtime) разрешения SET_WALLPAPER в
 * манифесте — пользователь ничего не подтверждает в системе, но без строки в манифесте
 * WallpaperManager бросит SecurityException. IMPORTANT (меняет системное состояние).
 */
class SetWallpaperTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "set_wallpaper"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """установить обои из картинки песочницы; args: {"path":"image.png","target":"home|lock|both"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val f = resolve(o.optString("path")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!f.exists() || !f.isFile) return@withContext ToolResult.Failure("нет файла: ${f.name}")

        val target = o.optString("target").ifBlank { "both" }.lowercase(Locale.ROOT)
        val flag = when (target) {
            "home", "system" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both", "all", "" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> return@withContext ToolResult.Failure("target: home | lock | both")
        }

        val bmp = BitmapFactory.decodeFile(f.absolutePath)
            ?: return@withContext ToolResult.Failure("не смог декодировать картинку: ${f.name}")

        val result = runCatching {
            // allowBackup=true, visibleCropHint=null → система сама впишет по экрану.
            WallpaperManager.getInstance(context).setBitmap(bmp, null, true, flag)
        }
        bmp.recycle()
        result.fold(
            onSuccess = {
                ToolResult.Success("""{"path":"${escapeJson(f.name)}","target":"$target","ok":true}""")
            },
            onFailure = {
                // Нет разрешения SET_WALLPAPER в манифесте → SecurityException.
                ToolResult.Failure("не удалось установить обои: ${it.message?.take(160)}")
            },
        )
    }
}

// -------------------------- pdf_sign (криптографическая подпись) --------------------------

/**
 * pdf_sign — поставить на PDF НАСТОЯЩУЮ криптографическую подпись (самоподписанный флоу).
 * Генерируется/переиспользуется самоподписанный сертификат пользователя, документ подписывается
 * detached-контейнером PKCS#7 (CMS SignedData) по стандарту PDFBox. IMPORTANT (создаёт файл).
 */
class PdfSignTool(
    private val context: Context,
    private val resolve: (String) -> File?,
) : AgentTool {
    override val id = "pdf_sign"
    override val danger = DangerLevel.IMPORTANT
    override val usesFiles = true
    override val schema =
        """поставить на PDF криптографическую подпись (самоподписанный X.509, PKCS7 detached); """ +
            """args: {"input":"doc.pdf","out":"signed.pdf","reason":"опц","location":"опц"}"""

    override suspend fun invoke(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext ToolResult.Failure("битый JSON")
        val input = resolve(o.optString("input")) ?: return@withContext ToolResult.Failure("путь вне папки")
        if (!input.exists() || !input.isFile) return@withContext ToolResult.Failure("нет файла: ${input.name}")
        val out = resolve(o.optString("out").ifBlank { "${input.nameWithoutExtension}_signed.pdf" })
            ?: return@withContext ToolResult.Failure("путь вне папки")
        val reason = o.optString("reason").trim().ifBlank { "Подписано в Plus AI" }
        val location = o.optString("location").trim()

        PdfBoxSign.ensure(context)
        val signedAt = Calendar.getInstance()
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(signedAt.time)

        // Личность пользователя (ключ + самоподписанный сертификат). При сбое крипто — честный Failure.
        val identity = runCatching { PdfIdentity.load(context) }.getOrElse {
            return@withContext ToolResult.Failure(
                "pdf_sign: не удалось подготовить сертификат (BouncyCastle): ${it.message?.take(200)}",
            )
        }

        val result = runCatching {
            out.parentFile?.mkdirs()
            PDDocument.load(input).use { doc ->
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    setName(identity.subject)
                    setReason(reason)
                    if (location.isNotBlank()) setLocation(location)
                    setSignDate(signedAt)
                }
                doc.addSignature(signature, Pkcs7Signer(identity))
                FileOutputStream(out).use { fos -> doc.saveIncremental(fos) }
            }
        }
        result.onFailure { return@withContext ToolResult.Failure("pdf_sign: ${it.message?.take(200)}") }
        ToolResult.Success(
            """{"path":"${escapeJson(out.name)}","kind":"cryptographic",""" +
                """"signer":"${escapeJson(identity.subject)}","algo":"SHA256withRSA",""" +
                """"signed_at":"${escapeJson(stamp)}"}""",
        )
    }
}

/**
 * SignatureInterface для PDFBox: собирает detached-контейнер PKCS#7 (CMS SignedData) над байтами,
 * которые PDFBox передаёт в [sign] (диапазон ByteRange). encapsulate=false → detached (adbe.pkcs7.detached).
 */
private class Pkcs7Signer(private val identity: PdfIdentity.Signer) : SignatureInterface {
    override fun sign(content: InputStream): ByteArray {
        val gen = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(identity.privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build()
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, identity.certificate),
        )
        gen.addCertificates(JcaCertStore(listOf(identity.certificate)))
        val signedData = gen.generate(CmsProcessableInputStream(content), false)
        return signedData.encoded
    }
}

/**
 * Обёртка входного потока в [CMSTypedData] для CMSSignedDataGenerator.generate(..., encapsulate=false).
 * Публичный BC-класс CMSProcessableInputStream реализует лишь CMSProcessable, а generate требует
 * CMSTypedData (нужен getContentType) — поэтому используется этот тонкий адаптер (штатный приём PDFBox).
 */
private class CmsProcessableInputStream(private val input: InputStream) : CMSTypedData {
    override fun getContentType(): ASN1ObjectIdentifier = PKCSObjectIdentifiers.data
    override fun getContent(): Any = input
    override fun write(out: OutputStream) {
        input.copyTo(out)
    }
}

/**
 * Личность пользователя для подписи PDF: самоподписанный RSA-2048 сертификат (SHA256withRSA, ~10 лет).
 * Генерируется один раз и кэшируется в filesDir как PKCS#12, чтобы у пользователя была стабильная
 * подпись между вызовами. Провайдер BouncyCastle регистрируется идемпотентно.
 */
private object PdfIdentity {
    private const val KS_FILE = "pdf_sign_identity.p12"
    private const val ALIAS = "plusai"
    private const val SUBJECT = "CN=Plus AI User, O=Plus AI"
    private val PWD = "plusai-pdf-identity".toCharArray()

    data class Signer(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val subject: String,
    )

    @Volatile private var cached: Signer? = null

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun load(context: Context): Signer {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            ensureProvider()
            val ksFile = File(context.filesDir, KS_FILE)
            val signer = (if (ksFile.exists()) runCatching { loadFrom(ksFile) }.getOrNull() else null)
                ?: create(ksFile)
            cached = signer
            return signer
        }
    }

    private fun loadFrom(ksFile: File): Signer {
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
        FileInputStream(ksFile).use { ks.load(it, PWD) }
        val key = ks.getKey(ALIAS, PWD) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return Signer(key, cert, cert.subjectX500Principal.name.ifBlank { SUBJECT })
    }

    private fun create(ksFile: File): Signer {
        val signer = generateSelfSigned()
        // Персист опционален: если запись PKCS#12 не удалась, подпись всё равно работает (без кэша на диске).
        runCatching {
            val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, signer.privateKey, PWD, arrayOf<java.security.cert.Certificate>(signer.certificate))
            FileOutputStream(ksFile).use { ks.store(it, PWD) }
        }
        return signer
    }

    private fun generateSelfSigned(): Signer {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)                 // -1 день на расхождение часов
        val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000)          // ~10 лет
        val dn = X500Name(SUBJECT)
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(dn, serial, notBefore, notAfter, dn, kp.public)
        val certSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(kp.private)
        val holder = builder.build(certSigner)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
        return Signer(kp.private, cert, SUBJECT)
    }
}

/** Ленивая идемпотентная инициализация PDFBox-android (грузит шрифты/ресурсы из assets). */
private object PdfBoxSign {
    @Volatile private var done = false
    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            PDFBoxResourceLoader.init(context.applicationContext)
            done = true
        }
    }
}

/**
 * Фабрика инструментов рабочего стола/подписи: set_wallpaper + pdf_sign.
 * pdf_sign — криптографическая подпись (самоподписанный X.509 + PKCS7 detached через BouncyCastle).
 */
fun wallpaperPdfSignTools(context: Context, resolve: (String) -> File?): List<AgentTool> = listOf(
    SetWallpaperTool(context, resolve),
    PdfSignTool(context, resolve),
)
