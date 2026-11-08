package com.example.util

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "DeviceDutyManager"

sealed class DutyResult {
    data class Success(val message: String, val tamilMessage: String, val actionType: ActionType) : DutyResult()
    data class Handled(val message: String) : DutyResult()
    data object NotADuty : DutyResult()
}

enum class ActionType {
    TORCH_ON, TORCH_OFF, OPEN_WIFI, OPEN_INTERNET, LAUNCH_APP, HI_KITTY
}

object DeviceDutyManager {

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    /**
     * Checks if the user's text or voice input matches a known device duty.
     * Executes the duty if matched, and returns the response.
     */
    fun processDutyCommand(context: Context, rawPrompt: String, isTamilMode: Boolean): DutyResult {
        val prompt = rawPrompt.lowercase().trim()

        // 1. "Hi Kitty" Wake phrase / greeting
        if (prompt.contains("hi kitty") || prompt.contains("kitty") || prompt.contains("ஹாய் கிட்டி")) {
            // Check if there is an embedded command after "hi kitty" (e.g. "hi kitty open youtube")
            val commandAfterWake = prompt
                .replace("hi kitty", "")
                .replace("kitty", "")
                .replace("ஹாய் கிட்டி", "")
                .trim()

            if (commandAfterWake.isNotBlank()) {
                val subResult = processDutyCommand(context, commandAfterWake, isTamilMode)
                if (subResult !is DutyResult.NotADuty) {
                    return subResult
                }
            }

            return DutyResult.Success(
                message = "Meow! Hi there, I'm listening. How can I help you?",
                tamilMessage = "ஹாய்! நான் கேட்டுக்கொண்டிருக்கிறேன். என்ன உதவி செய்ய வேண்டும்?",
                actionType = ActionType.HI_KITTY
            )
        }

        // 2. Torch / Flashlight Commands
        if (isTorchOnCommand(prompt)) {
            val success = setTorchState(context, true)
            return if (success) {
                DutyResult.Success(
                    message = "Flashlight turned on.",
                    tamilMessage = "டார்ச் ஆன் செய்யப்பட்டது.",
                    actionType = ActionType.TORCH_ON
                )
            } else {
                DutyResult.Success(
                    message = "Could not access flashlight on this device.",
                    tamilMessage = "இந்த சாதனத்தில் டார்ச்சை இயக்க முடியவில்லை.",
                    actionType = ActionType.TORCH_ON
                )
            }
        }

        if (isTorchOffCommand(prompt)) {
            val success = setTorchState(context, false)
            return if (success) {
                DutyResult.Success(
                    message = "Flashlight turned off.",
                    tamilMessage = "டார்ச் ஆஃப் செய்யப்பட்டது.",
                    actionType = ActionType.TORCH_OFF
                )
            } else {
                DutyResult.Success(
                    message = "Could not turn off flashlight.",
                    tamilMessage = "டார்ச்சை அணைக்க முடியவில்லை.",
                    actionType = ActionType.TORCH_OFF
                )
            }
        }

        // 3. Wi-Fi / Internet Settings Commands
        if (isWifiCommand(prompt)) {
            openWifiSettings(context)
            return DutyResult.Success(
                message = "Opening Wi-Fi Settings panel for you.",
                tamilMessage = "வைஃபை அமைப்புகளைத் திறக்கிறேன்.",
                actionType = ActionType.OPEN_WIFI
            )
        }

        if (isInternetCommand(prompt)) {
            openInternetSettings(context)
            return DutyResult.Success(
                message = "Opening Network & Internet settings.",
                tamilMessage = "இணைய மற்றும் நெட்வொர்க் அமைப்புகளைத் திறக்கிறேன்.",
                actionType = ActionType.OPEN_INTERNET
            )
        }

        // 4. App Launching Commands (Instagram, YouTube, WhatsApp, etc.)
        val appToLaunch = detectAppLaunch(prompt)
        if (appToLaunch != null) {
            val launched = launchApp(context, appToLaunch)
            return if (launched) {
                DutyResult.Success(
                    message = "Opening ${appToLaunch.displayName}...",
                    tamilMessage = "${appToLaunch.displayName}-ஐ திறக்கிறேன்...",
                    actionType = ActionType.LAUNCH_APP
                )
            } else {
                DutyResult.Success(
                    message = "Could not open ${appToLaunch.displayName}. Redirecting to Play Store...",
                    tamilMessage = "${appToLaunch.displayName}-ஐ திறக்க முடியவில்லை. Play Store-க்கு செல்கிறேன்...",
                    actionType = ActionType.LAUNCH_APP
                )
            }
        }

        return DutyResult.NotADuty
    }

    private fun isTorchOnCommand(prompt: String): Boolean {
        return prompt.contains("turn on torch") ||
                prompt.contains("torch on") ||
                prompt.contains("turn on flashlight") ||
                prompt.contains("flashlight on") ||
                prompt.contains("switch on torch") ||
                prompt.contains("டார்ச் போடு") ||
                prompt.contains("டார்ச் ஆன்") ||
                prompt.contains("விளக்கை ஏற்று")
    }

    private fun isTorchOffCommand(prompt: String): Boolean {
        return prompt.contains("turn off torch") ||
                prompt.contains("torch off") ||
                prompt.contains("turn off flashlight") ||
                prompt.contains("flashlight off") ||
                prompt.contains("switch off torch") ||
                prompt.contains("டார்ச் ஆப்") ||
                prompt.contains("டார்ச் அணை") ||
                prompt.contains("டார்ச்சை அணை")
    }

    private fun isWifiCommand(prompt: String): Boolean {
        return prompt.contains("turn on wifi") ||
                prompt.contains("open wifi") ||
                prompt.contains("wifi on") ||
                prompt.contains("wifi settings") ||
                prompt.contains("வைஃபை")
    }

    private fun isInternetCommand(prompt: String): Boolean {
        return prompt.contains("turn on internet") ||
                prompt.contains("open internet") ||
                prompt.contains("internet on") ||
                prompt.contains("mobile data") ||
                prompt.contains("network settings") ||
                prompt.contains("இணையம்")
    }

    fun setTorchState(context: Context, state: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return false
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return false

            cameraManager.setTorchMode(cameraId, state)
            _isTorchOn.value = state
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: $state", e)
            false
        }
    }

    fun openWifiSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Panel failed, opening fallback wifi settings", e)
            val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun openInternetSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Internet panel failed, opening wireless settings", e)
            val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    data class AppTarget(
        val displayName: String,
        val packageName: String,
        val webFallback: String
    )

    private fun detectAppLaunch(prompt: String): AppTarget? {
        val lower = prompt.lowercase()
        return when {
            lower.contains("instagram") || lower.contains("இன்ஸ்டாகிராம்") -> AppTarget(
                displayName = "Instagram",
                packageName = "com.instagram.android",
                webFallback = "https://www.instagram.com"
            )
            lower.contains("youtube") || lower.contains("யூடியூப்") -> AppTarget(
                displayName = "YouTube",
                packageName = "com.google.android.youtube",
                webFallback = "https://www.youtube.com"
            )
            lower.contains("whatsapp") || lower.contains("வாட்ஸ்அப்") -> AppTarget(
                displayName = "WhatsApp",
                packageName = "com.whatsapp",
                webFallback = "https://web.whatsapp.com"
            )
            lower.contains("spotify") || lower.contains("ஸ்பாட்டிஃபை") -> AppTarget(
                displayName = "Spotify",
                packageName = "com.spotify.music",
                webFallback = "https://open.spotify.com"
            )
            lower.contains("maps") || lower.contains("மேப்ஸ்") -> AppTarget(
                displayName = "Google Maps",
                packageName = "com.google.android.apps.maps",
                webFallback = "https://maps.google.com"
            )
            lower.contains("camera") || lower.contains("கேமரா") -> AppTarget(
                displayName = "Camera",
                packageName = "android.media.action.IMAGE_CAPTURE",
                webFallback = ""
            )
            // Generic "open [app]" detection
            lower.startsWith("open ") || lower.contains("திற") -> {
                val candidateName = lower.replace("open", "").replace("திற", "").trim()
                if (candidateName.isNotBlank() && candidateName.length > 2) {
                    AppTarget(
                        displayName = candidateName.replaceFirstChar { it.uppercase() },
                        packageName = candidateName,
                        webFallback = "https://play.google.com/store/search?q=$candidateName&c=apps"
                    )
                } else null
            }
            else -> null
        }
    }

    fun launchApp(context: Context, app: AppTarget): Boolean {
        try {
            val pm = context.packageManager
            // 1. Try launching by explicit package name
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return true
            }

            // 2. Try searching installed packages by app label
            val installedApps = pm.getInstalledApplications(0)
            val matched = installedApps.firstOrNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                label == app.displayName.lowercase() || label.contains(app.displayName.lowercase())
            }

            if (matched != null) {
                val intent = pm.getLaunchIntentForPackage(matched.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            }

            // 3. Fallback: Open in Google Play Store or Web
            val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (playStoreIntent.resolveActivity(pm) != null) {
                context.startActivity(playStoreIntent)
                return true
            }

            if (app.webFallback.isNotBlank()) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(app.webFallback)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: ${app.displayName}", e)
        }
        return false
    }
}
