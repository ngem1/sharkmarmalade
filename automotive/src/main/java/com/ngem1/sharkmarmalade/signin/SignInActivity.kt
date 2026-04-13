package com.ngem1.sharkmarmalade.signin

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.concurrent.futures.await
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.ngem1.sharkmarmalade.JellyfinMediaLibrarySessionCallback.Companion.LOGIN_COMMAND
import com.ngem1.sharkmarmalade.JellyfinMusicService
import com.ngem1.sharkmarmalade.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


@AndroidEntryPoint
class SignInActivity : AppCompatActivity() {

    private lateinit var viewModel: SignInActivityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        viewModel = ViewModelProvider(this)[SignInActivityViewModel::class.java]

        viewModel.loggedIn.observe(this) { loggedIn ->
            if (loggedIn == true) {
                val service = ComponentName(applicationContext, JellyfinMusicService::class.java)
                val future = MediaController.Builder(
                    applicationContext,
                    SessionToken(applicationContext, service)
                ).buildAsync()

                lifecycleScope.launch {
                    val controller = future.await()
                    controller.sendCustomCommand(SessionCommand(LOGIN_COMMAND, Bundle()), Bundle())
                    finish()
                }

            }
        }

        supportFragmentManager.beginTransaction()
            .add(R.id.sign_in_container, ServerSignInFragment())
            .commit()
    }
}