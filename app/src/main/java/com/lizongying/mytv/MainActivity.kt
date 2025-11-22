package com.lizongying.mytv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.json.JSONObject

class MainActivity : FragmentActivity(), Request.RequestListener {

    private var ready = 0
    private val playerFragment = PlayerFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()
    private val errorFragment = ErrorFragment()

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler()
    private val delayHideMain: Long = 10000
    private val delayHideSetting: Long = 10000

    // 标记APP是否已初始化，防止重复加载
    private var isAppInitialized = false

    init {
        lifecycleScope.launch(Dispatchers.IO) {
            val utilsJob = async(start = CoroutineStart.LAZY) { Utils.init() }
            utilsJob.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- 核心修改：启动时检查授权 ---
        checkAuthorization()
    }

    // ==========================================
    //       新增：授权与试用功能区域
    // ==========================================

    private fun checkAuthorization() {
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val savedCode = prefs.getString("auth_code", "")

        // 如果没有保存过授权码，或者保存的是空的（试用状态可能不存码，或者你想每次都验证），这里简单处理：
        // 如果想让试用期用户每次打开都检查时间，可以只判断 savedCode 是否为 null
        // 这里逻辑：如果没有码，弹窗；如果有码，去后台验证（后台会判断是试用还是正式）
        if (savedCode.isNullOrEmpty()) {
            showAuthDialog()
        } else {
            verifyCodeWithServer(savedCode)
        }
    }

    private fun showAuthDialog() {
        runOnUiThread {
            val input = EditText(this)
            input.hint = "请输入授权码"
            input.setTextColor(Color.BLACK)
            input.setPadding(50, 50, 50, 50)

            val builder = AlertDialog.Builder(this)
                .setTitle("应用未授权")
                .setMessage("请输入授权码，新用户可点击试用")
                .setView(input)
                .setCancelable(false) // 禁止点击外部关闭

                // 正式验证按钮
                .setPositiveButton("验证") { _, _ ->
                    val code = input.text.toString().trim()
                    if (code.isNotEmpty()) {
                        verifyCodeWithServer(code)
                    } else {
                        Toast.makeText(this, "授权码不能为空", Toast.LENGTH_SHORT).show()
                        showAuthDialog()
                    }
                }

                // 试用按钮
                .setNeutralButton("免费试用") { _, _ ->
                    // 传空字符串给后台，代表申请试用
                    verifyCodeWithServer("") 
                }

            builder.show()
        }
    }

    private fun verifyCodeWithServer(code: String) {
        val deviceId = Utils.getDeviceId(this)
        
        // !!! 请务必修改成你自己的后台地址 !!!
        val url = "http://iptv.dwz12.top/api/get_config.php" 

        val json = "{\"androidIdStr\":\"$deviceId\", \"authCode\":\"$code\"}"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val body = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    json
                )
                val request = okhttp3.Request.Builder().url(url).post(body).build()
                val response = client.newCall(request).execute()
                val respStr = response.body()?.string()

                if (response.isSuccessful && respStr != null) {
                    try {
                        val jsonObject = JSONObject(respStr)
                        val codeStatus = jsonObject.optInt("code")
                        val msg = jsonObject.optString("msg")

                        if (codeStatus == 200) {
                            // 验证成功 (包含试用成功)
                            val data = jsonObject.optJSONObject("data")
                            val days = data?.optInt("remaining_days") ?: 0
                            val encryptedConfig = data?.optString("config") ?: ""

                            // 如果后台下发了加密配置，解密使用
                            if (encryptedConfig.isNotEmpty()) {
                                val decryptedConfig = Utils.decryptData(encryptedConfig)
                                Log.i(TAG, "配置解密成功: $decryptedConfig")
                                // TODO: 解析 config JSON 并设置直播源
                            }

                            // 如果是正式授权码，保存起来；如果是试用（code为空），也可以保存一个标记
                            if (code.isNotEmpty()) {
                                getSharedPreferences("app_config", Context.MODE_PRIVATE)
                                    .edit().putString("auth_code", code).apply()
                            } else {
                                // 试用模式，我们可以存一个特定标记，或者不存，每次启动都点试用（后台会控制时间）
                                // 这里为了方便，试用也存空字符串或者特定字符，视你需求而定
                                // 如果想让用户每次都确认试用，可以不存。这里暂时不存。
                            }

                            runOnUiThread {
                                val tips = if (code.isEmpty()) "试用已开启" else "验证成功"
                                Toast.makeText(this@MainActivity, "$tips，剩余 $days 天", Toast.LENGTH_LONG).show()
                                // 验证通过，进入APP
                                initAppUI()
                            }
                        } else {
                            // 验证失败（过期、被封、无效码）
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "验证失败: $msg", Toast.LENGTH_LONG).show()
                                // 清除无效的旧授权码
                                getSharedPreferences("app_config", Context.MODE_PRIVATE)
                                    .edit().remove("auth_code").apply()
                                showAuthDialog()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "数据解析错误", Toast.LENGTH_SHORT).show()
                            showAuthDialog()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "服务器连接失败: ${response.code()}", Toast.LENGTH_SHORT).show()
                        showAuthDialog()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "网络错误，无法验证", Toast.LENGTH_SHORT).show()
                    showAuthDialog()
                }
            }
        }
    }

    // ==========================================
    //       原有的 APP 初始化逻辑 (移到这里)
    // ==========================================

    private fun initAppUI() {
        if (isAppInitialized) return
        isAppInitialized = true

        Request.setRequestListener(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION

        // 原 onCreate 中的 Fragment 加载逻辑
        if (supportFragmentManager.fragments.isEmpty()) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .commit()
        }
        
        gestureDetector = GestureDetector(this, GestureListener())

        errorFragment.buttonClickListener = View.OnClickListener {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
                    if (this@MainActivity.isNetworkConnected) {
                        Log.i(TAG, "net isNetworkConnected")
                        ready++
                    }
                }
            })
        } else {
            Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
            ready++
        }
    }

    // ==========================================
    //       以下保持原版代码完全不变
    // ==========================================

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        if (SP.channelNum) {
            channelFragment.show(tvViewModel)
        }
    }

    private fun showChannel(channel: String) {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        if (SP.channelNum) {
            channelFragment.show(channel)
        }
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
        mainFragment.view?.requestFocus()
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    private fun prevSource() {
//        mainFragment.prevSource()
    }

    private fun nextSource() {
//        mainFragment.nextSource()
    }

    fun switchMainFragment() {
        val transaction = supportFragmentManager.beginTransaction()

        if (mainFragment.isHidden) {
            transaction.show(mainFragment)
            mainActive()
        } else {
            transaction.hide(mainFragment)
        }

        transaction.commit()
    }

    fun mainActive() {
        handler.removeCallbacks(hideMain)
        handler.postDelayed(hideMain, delayHideMain)
    }

    fun settingDelayHide() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
        showTime()
    }

    fun settingHideNow() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, 0)
    }

    fun settingNeverHide() {
        handler.removeCallbacks(hideSetting)
    }

    private val hideMain = Runnable {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction().hide(mainFragment).commit()
        }
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    fun fragmentReady(tag: String) {
        ready++
        Log.i(TAG, "ready $tag $ready ")
        if (ready == 6) {
            mainFragment.fragmentReady()
            showTime()
        }
    }

    private fun showTime() {
        Log.i(TAG, "showTime ${SP.time}")
        if (SP.time) {
            timeFragment.show()
        } else {
            timeFragment.hide()
        }
    }

    fun isPlaying() {
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            switchMainFragment()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (velocityY > 0) {
                if (mainFragment.isHidden) {
                    prev()
                } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
                }
            }
            if (velocityY < 0) {
                if (mainFragment.isHidden) {
                    next()
                } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    private fun showSetting() {
        if (!mainFragment.isHidden) {
            return
        }

        Log.i(TAG, "settingFragment ${settingFragment.isVisible}")
        if (!settingFragment.isVisible) {
            settingFragment.show(supportFragmentManager, "setting")
            settingDelayHide()
        } else {
            handler.removeCallbacks(hideSetting)
            settingFragment.dismiss()
        }
    }

    private val hideSetting = Runnable {
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        }
    }

    private fun channelUp() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
        }
    }

    private fun channelDown() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
        }
    }

    private fun back() {
        if (!mainFragmentIsHidden()) {
            hideMainFragment()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "keyCode $keyCode, event $event")
        when (keyCode) {
            KeyEvent.KEYCODE_0 -> {
                showChannel("0")
                return true
            }

            KeyEvent.KEYCODE_1 -> {
                showChannel("1")
                return true
            }

            KeyEvent.KEYCODE_2 -> {
                showChannel("2")
                return true
            }

            KeyEvent.KEYCODE_3 -> {
                showChannel("3")
                return true
            }

            KeyEvent.KEYCODE_4 -> {
                showChannel("4")
                return true
            }

            KeyEvent.KEYCODE_5 -> {
                showChannel("5")
                return true
            }

            KeyEvent.KEYCODE_6 -> {
                showChannel("6")
                return true
            }

            KeyEvent.KEYCODE_7 -> {
                showChannel("7")
                return true
            }

            KeyEvent.KEYCODE_8 -> {
                showChannel("8")
                return true
            }

            KeyEvent.KEYCODE_9 -> {
                showChannel("9")
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    switchMainFragment()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    showSetting()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun getAppSignature() = "my_signature"

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        if (!mainFragment.isHidden) {
            handler.postDelayed(hideMain, delayHideMain)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
        handler.removeCallbacks(hideMain)
    }

    override fun onDestroy() {
        super.onDestroy()
        Request.onDestroy()
    }

    override fun onRequestFinished(message: String?) {
        if (message != null && !errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
            errorFragment.setErrorContent(message)
        }
        fragmentReady("Request")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
