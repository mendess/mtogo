package xyz.mendess.mtogo.ui.androidauto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class AndroidAutoService: CarAppService() {
    override fun onCreateSession(): Session {
        return AndroidAutoSession()
    }
    override fun createHostValidator(): HostValidator {
        TODO("Not yet implemented")
    }
}

class AndroidAutoSession(): Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        TODO("Not yet implemented")
    }

}