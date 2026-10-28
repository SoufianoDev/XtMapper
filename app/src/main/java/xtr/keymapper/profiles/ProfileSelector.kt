package xtr.keymapper.profiles

import xtr.keymapper.log.DiagnosticLog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.UiContext
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xtr.keymapper.R
import xtr.keymapper.databinding.AppViewBinding
import xtr.keymapper.databinding.TextFieldBinding
import xtr.keymapper.keymap.KeymapProfiles

class ProfileSelector {

    fun interface OnProfileSelectedListener {
        fun onProfileSelected(profile: String)
    }

    fun interface OnAppSelectedListener {
        fun onAppSelected(packageName: String)
    }

    fun interface OnProfileEnabledListener {
        fun onEnabled(enabled: Boolean)
    }

    companion object {

        fun select(context: Context, listener: OnProfileSelectedListener, packageName: String) {
            context.setTheme(R.style.Theme_XtMapper)
            val allProfiles = ArrayList(
                KeymapProfiles(context).getAllProfilesForApp(packageName).keys
            )
            DiagnosticLog.info("ProfileSelector", "profile_selection_requested", "package" to packageName, "optionCount" to allProfiles.size)

            if (allProfiles.size == 1) {
                DiagnosticLog.info("ProfileSelector", "profile_selection_auto_resolved", "package" to packageName, "profile" to allProfiles[0], "reason" to "single_option")
                listener.onProfileSelected(allProfiles[0])
                return
            } else if (allProfiles.isEmpty()) {
                DiagnosticLog.info("ProfileSelector", "profile_creation_prompted", "package" to packageName, "reason" to "no_existing_profile")
                createNewProfileForApp(context, packageName, true, listener)
                return
            }
            val items = allProfiles.toTypedArray()

            val pm = context.packageManager

            var appName: CharSequence = ""
            var appIcon: Drawable? = null
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
                appIcon = pm.getApplicationIcon(packageName)
            } catch (ignored: PackageManager.NameNotFoundException) {
            }
            // Show dialog to select profile
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(context.getString(R.string.dialog_alert_select_profile) + appName)
                .setItems(items) { _, which ->
                    val selectedProfile = allProfiles[which]
                    DiagnosticLog.info("ProfileSelector", "profile_selected_in_dialog", "package" to packageName, "profile" to selectedProfile, "index" to which)
                    listener.onProfileSelected(selectedProfile)
                }
                .setIcon(appIcon)
            showDialog(builder)
        }

        fun createNewProfile(@UiContext context: Context, listener: OnProfileSelectedListener) {
            DiagnosticLog.info("ProfileSelector", "create_profile_flow_started")
            showAppSelectionDialog(context) { packageName ->
                DiagnosticLog.info("ProfileSelector", "create_profile_app_chosen", "package" to packageName)
                createNewProfileForApp(context, packageName, true, listener)
            }
        }

        fun showEnableProfileDialog(
            @UiContext context: Context,
            packageName: String,
            listener: OnProfileEnabledListener
        ) {
            DiagnosticLog.info("ProfileSelector", "profile_enable_prompted", "package" to packageName)
            context.setTheme(R.style.Theme_XtMapper)
            val builder = MaterialAlertDialogBuilder(context)
            val binding = AppViewBinding.inflate(LayoutInflater.from(context))
            val pm = context.packageManager
            try {
                binding.appName.text = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
                binding.appIcon.setImageDrawable(pm.getApplicationIcon(packageName))
            } catch (ignored: PackageManager.NameNotFoundException) {
            }

            builder.setTitle(R.string.dialog_alert_enable_profile)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DiagnosticLog.info("ProfileSelector", "profile_enable_prompt_answered", "package" to packageName, "enabled" to true)
                    listener.onEnabled(true)
                }
                .setNegativeButton(R.string.no) { _, _ ->
                    DiagnosticLog.info("ProfileSelector", "profile_enable_prompt_answered", "package" to packageName, "enabled" to false)
                    listener.onEnabled(false)
                }
                .setView(binding.root)
            showDialog(builder)
        }

        fun createNewProfileForApp(
            @UiContext context: Context,
            packageName: String,
            enabled: Boolean,
            listener: OnProfileSelectedListener
        ) {
            DiagnosticLog.info("ProfileSelector", "profile_name_prompted", "package" to packageName, "enabled" to enabled)
            val builder = MaterialAlertDialogBuilder(context)
            val binding = TextFieldBinding.inflate(LayoutInflater.from(context))
            binding.root.setHint(R.string.profile_name)
            binding.editText.setText(packageName)

            builder.setTitle(R.string.dialog_alert_add_profile)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val selectedProfile = binding.editText.text.toString()
                    DiagnosticLog.info("ProfileSelector", "profile_creation_confirmed", "package" to packageName, "profile" to selectedProfile, "enabled" to enabled)
                    val keymapProfiles = KeymapProfiles(context)
                    keymapProfiles.saveProfile(selectedProfile, arrayListOf(), packageName, enabled, 0, 0)
                    listener.onProfileSelected(selectedProfile)
                }
                .setView(binding.root)
            showDialog(builder)
        }

        // Show a dialog with a list of apps and callback when an app is selected
        fun showAppSelectionDialog(context: Context, listener: OnAppSelectedListener) {
            DiagnosticLog.info("ProfileSelector", "app_selection_dialog_requested")
            val builder = MaterialAlertDialogBuilder(context)
            ProfilesApps.asyncLoadAppsAndThen(context, builder) { p, adapter, loadingDialog ->
                p.binding.appsGrid.adapter = adapter
                loadingDialog.dismiss()
                val dialog = builder.setView(p.appsView).show()

                p.setListener {
                    DiagnosticLog.info("ProfileSelector", "app_selected", "package" to it)
                    listener.onAppSelected(it)
                    p.onDestroyView()
                    dialog.dismiss()
                }
            }
        }

        fun showDialog(builder: MaterialAlertDialogBuilder): AlertDialog {
            val dialog = builder.create()
            if (Settings.canDrawOverlays(dialog.context))
                dialog.window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                )
            dialog.show()
            return dialog
        }
    }
}
