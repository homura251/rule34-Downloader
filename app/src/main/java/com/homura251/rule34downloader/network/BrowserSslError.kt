package com.homura251.rule34downloader.network

import android.net.http.SslError

internal fun browserSslError(error: SslError): String {
    val reason = when (error.primaryError) {
        SslError.SSL_NOTYETVALID -> "证书尚未生效，请检查设备日期和时间"
        SslError.SSL_EXPIRED -> "证书已过期，请检查设备日期和时间"
        SslError.SSL_IDMISMATCH -> "证书域名与请求地址不一致"
        SslError.SSL_UNTRUSTED -> "系统不信任此证书，请检查网络或代理配置"
        SslError.SSL_DATE_INVALID -> "证书日期无效，请检查设备日期和时间"
        else -> "证书验证失败"
    }
    return "网页 TLS 连接失败（${error.primaryError}）：$reason。地址：${BrowserReadDiagnostics.safeUrl(error.url)}"
}
