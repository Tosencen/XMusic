package com.xmgo.music.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xmgo.music.presentation.components.FirstRunDisclaimerDialog
import com.xmgo.music.presentation.navigation.Nav
import com.xmgo.music.presentation.theme.XmgoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app_main", MODE_PRIVATE)
        setContent {
            XmgoTheme {
                // 首次安装启动：先强制阅读免责声明，同意后才进入应用；不同意退出
                var disclaimerAgreed by remember {
                    mutableStateOf(prefs.getBoolean(KEY_DISCLAIMER_AGREED, false))
                }
                if (disclaimerAgreed) {
                    Nav()
                    // 应用自更新对话框（观察 AppUpdater.pendingUpdate，无新版时零开销）
                    com.xmgo.music.update.AppUpdateDialogHost()
                } else {
                    FirstRunDisclaimerDialog(
                        onAgree = {
                            prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, true).apply()
                            disclaimerAgreed = true
                        },
                        onDecline = { finishAffinity() }
                    )
                }
            }
        }
    }

    companion object {
        private const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed"
    }
}
