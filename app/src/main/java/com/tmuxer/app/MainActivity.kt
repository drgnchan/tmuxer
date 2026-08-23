package com.tmuxer.app

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tmuxer.app.ui.TmuxerApp
import com.tmuxer.app.ui.TmuxerViewModel
import com.tmuxer.app.ui.theme.TmuxerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TmuxerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TmuxerTheme {
                TmuxerApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        hideKeyboardAndClearFocus()
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    private fun hideKeyboardAndClearFocus() {
        val focusedView = currentFocus
        (focusedView?.windowToken ?: window.decorView.windowToken)?.let { token ->
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(token, 0)
        }
        focusedView?.clearFocus()
    }
}
