package com.anomalyco.opencode.data.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Raised when a repository call needs a server but none is configured. */
class NoServerConfiguredException :
    Exception("Önce bir sunucuya bağlanın.")

/**
 * Shared mapping of transport/API failures to human-readable messages that
 * make sense in the UI (kept in Turkish to match Phase 1 strings).
 */
internal fun Throwable.toFriendlyApiException(): Exception = when (this) {
    is UnknownHostException ->
        Exception("Sunucu adresi çözümlenemedi — URL ve internet bağlantısını kontrol et.", this)
    is ConnectException ->
        Exception("Sunucuya bağlanılamadı — OpenCode servisinin çalıştığından emin ol.", this)
    is SocketTimeoutException ->
        Exception("Bağlantı zaman aşımına uğradı — sunucu yavaş veya erişilemez.", this)
    is SSLException ->
        Exception("Güvenli bağlantı kurulamadı — sertifikayı ve HTTPS adresini kontrol et.", this)
    is OpenCodeHttpException -> when (code) {
        401, 403 -> Exception("Kimlik doğrulama başarısız — sunucu token'ını kontrol et.", this)
        404 -> Exception("Kaynak sunucuda bulunamadı.", this)
        429 -> Exception("Sunucu çok fazla istek aldı — biraz bekleyip tekrar dene.", this)
        in 500..599 -> Exception("Sunucu hatası (HTTP $code) — OpenCode servisini kontrol et.", this)
        else -> Exception("Sunucu hatası: HTTP $code", this)
    }
    else -> Exception(message ?: "Bilinmeyen bir hata oluştu.", this)
}
