package com.lizongying.mytv

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.TypedValue
import com.google.gson.Gson
import com.lizongying.mytv.api.TimeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Utils {
    private var between: Long = 0

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(
            format,
            Locale.CHINA
        ).format(Date(System.currentTimeMillis() - between))
    }

    fun getDateTimestamp(): Long {
        return (System.currentTimeMillis() - between) / 1000
    }

    fun setBetween(currentTimeMillis: Long) {
        between = System.currentTimeMillis() - currentTimeMillis
    }

    suspend fun init() {
        var currentTimeMillis: Long = 0
        try {
            currentTimeMillis = getTimestampFromServer()
        } catch (e: Exception) {
            println("Failed to retrieve timestamp from server: ${e.message}")
        }
        between = System.currentTimeMillis() - currentTimeMillis
    }

    /**
     * 从服务器获取时间戳
     * @return Long 时间戳
     */
    private suspend fun getTimestampFromServer(): Long {
        return withContext(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = okhttp3.Request.Builder()
                .url("https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    val string = response.body()?.string()
                    Gson().fromJson(string, TimeResponse::class.java).data.t.toLong()
                }
            } catch (e: IOException) {
                // Handle network errors
                throw IOException("Error during network request", e)
            }
        }
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun pxToDp(px: Float): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    fun pxToDp(px: Int): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    fun isTmallDevice() = Build.MANUFACTURER.equals("Tmall", ignoreCase = true)

    // --- 新增：获取设备唯一ID ---
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_ID"
    }

    // --- 新增：AES解密方法 ---
    fun decryptData(encryptedStr: String): String {
        // 这是从你的 app_configpub.php 获取的 AES Key
        val key = "82765ed657584b3202ec7667ff233fa945f48f7920781180614c794cdc489b83"
        
        try {
            var target = encryptedStr
            // 去除可能的前缀
            if (target.startsWith("ENC:")) {
                target = target.substring(4)
            }
            
            val cipherData = Base64.decode(target, Base64.DEFAULT)
            // 长度校验
            if (cipherData.size < 17) return ""

            // 提取 IV (前16字节) 和 密文
            val iv = Arrays.copyOfRange(cipherData, 0, 16)
            val cipherBytes = Arrays.copyOfRange(cipherData, 16, cipherData.size)

            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(iv)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            
            return String(cipher.doFinal(cipherBytes))
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}
