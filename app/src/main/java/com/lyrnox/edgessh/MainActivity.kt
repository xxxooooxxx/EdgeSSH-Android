package com.lyrnox.edgessh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lyrnox.edgessh.ui.AppNav
import com.lyrnox.edgessh.ui.theme.EdgeSshTheme

/** 应用唯一 Activity：全屏 Compose 入口。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeSshTheme {
                AppNav()
            }
        }
    }
}
