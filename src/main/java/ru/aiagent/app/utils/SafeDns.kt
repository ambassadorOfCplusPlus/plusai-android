package ru.aiagent.app.utils

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Общий клиент с анти-SSRF/анти-rebinding резолвером (SafeDns): резолв один раз, все адреса проверены,
// соединение идёт на них же; редиректы OkHttp перепроверяет по хопам. Один на все сетевые тулы приложения
// (http_request, download_file, generate_image), чтобы ни один не ходил в сеть мимо анти-SSRF.
internal val safeHttpClient: OkHttpClient = OkHttpClient.Builder()
    .dns(SafeDns())
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

// isSiteLocalAddress НЕ покрывает CGNAT 100.64.0.0/10 (carrier-NAT/hotspot) и IPv6 ULA fc00::/7 (для v6
// проверяется устаревший fec0::/10). Добавляем эти диапазоны явно по байтам адреса — иначе хост с
// A-записью в 100.64.x.x бил бы по внутреннему шлюзу мимо анти-SSRF.
internal fun isExtraPrivate(a: InetAddress): Boolean {
    val b = a.address ?: return false
    return when (b.size) {
        4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127 // 100.64.0.0/10
        16 -> (b[0].toInt() and 0xFE) == 0xFC                                     // fc00::/7 (fc/fd)
        else -> false
    }
}

// Пер-адресный предикат «локальный/внутренний» — общий фундамент анти-SSRF.
internal fun isBlockedAddress(a: InetAddress): Boolean =
    a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
        isExtraPrivate(a)

/**
 * OkHttp-резолвер, закрывающий DNS-rebinding (TOCTOU) вместе с анти-SSRF.
 *
 * ПРОБЛЕМА. Ручная проверка «резолвим имя → сверяем → openConnection() резолвит СНОВА» оставляла окно:
 * вредоносный DNS с коротким TTL мог вернуть публичный IP на проверку и приватный (127.0.0.1 /
 * 169.254.169.254) на само соединение.
 *
 * РЕШЕНИЕ. Резолвим РОВНО ОДИН РАЗ, проверяем ВСЕ полученные адреса и возвращаем именно их — OkHttp
 * соединяется на этот же набор, второго резолвинга ОС нет. При редиректах OkHttp зовёт resolver для
 * каждого нового хоста, поэтому анти-SSRF держится по-хоповно. SNI и валидация сертификата остаются по
 * ИМЕНИ хоста (в отличие от подстановки IP в URL), поэтому Cloudflare/виртуальные хосты не ломаются.
 */
class SafeDns(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addrs = try { resolve(hostname) } catch (e: Exception) { throw UnknownHostException(hostname) }
        if (addrs.isEmpty()) throw UnknownHostException(hostname)
        // ЛЮБОЙ внутренний адрес в ответе → отказ целиком (смесь публичного и внутреннего — обманка rebinding).
        if (addrs.any { isBlockedAddress(it) })
            throw IOException("доступ к локальным/внутренним адресам запрещён")
        return addrs
    }
}
