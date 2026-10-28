package xtr.keymapper.keymap

import xtr.keymapper.log.DiagnosticLog
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import xtr.keymapper.keymap.element.Camera
import xtr.keymapper.keymap.element.Dpad
import xtr.keymapper.keymap.element.Key
import xtr.keymapper.keymap.element.MouseAimConfig
import xtr.keymapper.keymap.element.MouseWalk
import xtr.keymapper.keymap.element.ShiftKey
import xtr.keymapper.keymap.element.SwipeKey
import xtr.keymapper.macro.Macro
import xtr.keymapper.macro.MacroIdUtils

class KeymapProfiles @JvmOverloads constructor(
    context: Context,
    @JvmField val sharedPref: SharedPreferences = context.getSharedPreferences("profiles", MODE_PRIVATE)
) {

    private val context: Context = context

    @Suppress("UNCHECKED_CAST")
    fun getAllProfiles(): Map<String, KeymapProfile> {
        val allProfiles = HashMap<String, KeymapProfile>()
        sharedPref.all.forEach { (key, lines) ->
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) return@forEach
            if (lines is Set<*>) {
                allProfiles[key] = getProfile(lines as Set<String>)
            }
        }
        return allProfiles
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllProfilesForApp(packageName: String): Map<String, KeymapProfile> {
        val appProfiles = HashMap<String, KeymapProfile>()
        sharedPref.all.forEach { (key, lines) ->
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) return@forEach
            if (lines is Set<*>) {
                val profile = getProfile(lines as Set<String>)
                if (profile.packageName == packageName)
                    appProfiles[key] = profile
            }
        }
        return appProfiles
    }

    fun getDesignatedProfileForApp(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        val designationKey = "designated_profile_$packageName"
        val designated = sharedPref.getString(designationKey, null)
        val profiles = getAllProfilesForApp(packageName)
        if (profiles.isEmpty()) {
            if (designated != null) {
                DiagnosticLog.warn("KeymapProfiles", "stale_designation_removed", "package" to packageName, "profile" to designated, "reason" to "no_profiles_for_package")
                sharedPref.edit().remove(designationKey).apply()
            }
            return null
        }
        if (designated != null && profiles.containsKey(designated)) {
            val profile = profiles[designated]
            if (profile != null && !profile.disabled) {
                return designated
            }
            // Keep a user's existing designation when that profile is temporarily disabled.
            // Return an enabled fallback for this launch, but do not persist over their choice.
            val fallback = profiles.entries.firstOrNull { !it.value.disabled }?.key
                ?: profiles.keys.firstOrNull()
            DiagnosticLog.info("KeymapProfiles", "designated_profile_fallback", "package" to packageName, "requested" to designated, "resolved" to fallback, "requestedDisabled" to (profile?.disabled == true))
            return fallback
        }

        val fallback = profiles.entries.firstOrNull { !it.value.disabled }?.key
            ?: profiles.keys.firstOrNull()
        if (fallback != null) {
            // Repair stale or disabled designations so future saves can keep the choice stable.
            DiagnosticLog.info("KeymapProfiles", "designated_profile_repaired", "package" to packageName, "previous" to designated, "resolved" to fallback)
            setDesignatedProfileForApp(packageName, fallback)
        } else if (designated != null) {
            sharedPref.edit().remove(designationKey).apply()
        }
        return fallback
    }

    fun setDesignatedProfileForApp(packageName: String?, profileName: String?) {
        if (!packageName.isNullOrEmpty() && profileName != null) {
            val key = "designated_profile_$packageName"
            val previous = sharedPref.getString(key, null)
            if (previous != profileName) {
                DiagnosticLog.info("KeymapProfiles", "designated_profile_changed", "package" to packageName, "previous" to previous, "profile" to profileName)
                sharedPref.edit().putString(key, profileName).apply()
            }
        } else {
            DiagnosticLog.warn("KeymapProfiles", "designated_profile_rejected", "package" to packageName, "profile" to profileName, "reason" to "missing_package_or_profile")
        }
    }

    fun renameProfile(profileName: String, newProfile: String) {
        DiagnosticLog.info("KeymapProfiles", "profile_rename_requested", "previous" to profileName, "new" to newProfile, "exists" to profileExists(profileName))
        val designatedKeys = sharedPref.all
            .filter { (key, value) -> key.startsWith("designated_profile_") && value == profileName }
            .keys
        val wasLastSelected = sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null) == profileName
        val stringSet = sharedPref.getStringSet(profileName, null)
        deleteProfile(profileName)
        sharedPref.edit()
            .putStringSet(newProfile, stringSet)
            .also { editor -> designatedKeys.forEach { editor.putString(it, newProfile) } }
            .apply()
        if (wasLastSelected) {
            setLastSelectedProfile(newProfile)
        }
    }

    fun setProfilePackageName(profileName: String?, packageName: String) {
        val stringSet = sharedPref.getStringSet(profileName, null)
        if (stringSet == null) {
            DiagnosticLog.warn("KeymapProfiles", "profile_package_change_rejected", "profile" to profileName, "newPackage" to packageName, "reason" to "profile_missing")
            return
        }
        val previous = getProfile(stringSet).packageName
        DiagnosticLog.info("KeymapProfiles", "profile_package_changed", "profile" to profileName, "previousPackage" to previous, "newPackage" to packageName)
        saveProfile(profileName, ArrayList<String?>(stringSet), packageName, stringSet.contains("ENABLED"), 0, 0)
    }

    fun isProfileEnabled(profileName: String?): Boolean {
        val stringSet = sharedPref.getStringSet(profileName, null)
        return stringSet?.contains("ENABLED") == true
    }

    fun profileExists(profileName: String?): Boolean {
        if (profileName.isNullOrEmpty()) return false
        return sharedPref.contains(profileName) && sharedPref.getStringSet(profileName, null) != null
    }

    fun profileExistsWithPackageName(packageName: String): Boolean {
        return getAllProfilesForApp(packageName).isNotEmpty()
    }

    fun getLastSelectedProfile(): String {
        val last = sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null)
        if (last != null && profileExists(last)) {
            return last
        }
        return getAllProfiles().keys.firstOrNull() ?: DEFAULT_PROFILE_NAME
    }

    internal fun getStartupProfileName(isShellInit: Boolean): String {
        return if (isShellInit) DEFAULT_PROFILE_NAME else getLastSelectedProfile()
    }

    internal fun recordStartupProfileSelection(profileName: String?, isShellInit: Boolean) {
        DiagnosticLog.info("KeymapProfiles", "startup_profile_resolved", "profile" to profileName, "source" to if (isShellInit) "trusted_shell_default" else "last_selected_or_default")
        if (!isShellInit) setLastSelectedProfile(profileName)
    }

    fun setLastSelectedProfile(profileName: String?) {
        if (!profileName.isNullOrEmpty()) {
            val previous = sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null)
            if (previous != profileName) {
                DiagnosticLog.info("KeymapProfiles", "last_selected_profile_changed", "previous" to previous, "profile" to profileName)
                sharedPref.edit().putString(KEY_LAST_SELECTED_PROFILE, profileName).apply()
            }
        } else {
            DiagnosticLog.warn("KeymapProfiles", "last_selected_profile_rejected", "reason" to "empty_profile_name")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun setProfileEnabled(profileName: String, enabled: Boolean) {
        val stringSet = sharedPref.getStringSet(profileName, null)
        if (stringSet == null) {
            DiagnosticLog.warn("KeymapProfiles", "profile_enable_change_rejected", "profile" to profileName, "enabled" to enabled, "reason" to "profile_missing")
            return
        }
        DiagnosticLog.info("KeymapProfiles", "profile_enable_change_requested", "profile" to profileName, "enabled" to enabled)
        var packageName: String? = null
        for (line in stringSet) {
            val data = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (data[0] == "APPLICATION" && data.size >= 2)
                packageName = data[1]
        }
        if (packageName == null) {
            DiagnosticLog.warn("KeymapProfiles", "profile_enable_change_rejected", "profile" to profileName, "enabled" to enabled, "reason" to "package_missing")
            return
        }

        for ((key, value) in sharedPref.all) {
            if (key.startsWith("designated_profile_") || key == KEY_LAST_SELECTED_PROFILE) continue
            if (value !is Set<*>) continue
            // disable or enable all profiles for app for consistency
            val profile = getProfile(value as Set<String>)
            if (profile.packageName == packageName) {
                val lines = sharedPref.getStringSet(key, null) ?: continue
                saveProfile(key, ArrayList<String?>(lines), packageName, enabled, profile.xRes, profile.yRes)
            }
        }
    }

    fun saveProfile(
        profileName: String?,
        lines: ArrayList<String?>,
        packageName: String,
        enabled: Boolean,
        xRes: Int,
        yRes: Int
    ) {
        DiagnosticLog.info("KeymapProfiles", "profile_save_requested", "profile" to profileName, "package" to packageName, "enabled" to enabled, "exists" to profileExists(profileName), "lineCount" to lines.size, "screenWidth" to xRes, "screenHeight" to yRes)
        lines.removeIf { it!!.contains("APPLICATION") }
        lines.add("APPLICATION $packageName")

        if (xRes > 0 && yRes > 0) {
            lines.removeIf { it!!.contains("SCREENSIZE") }
            lines.add("SCREENSIZE $xRes $yRes")
        }

        lines.removeIf { it!!.contains("ENABLED") }
        if (enabled) lines.add("ENABLED")
        val stringSet = HashSet(lines)
        sharedPref.edit()
            .putStringSet(profileName, stringSet)
            .apply()

        if (!packageName.isNullOrEmpty()) {
            if (sharedPref.getString("designated_profile_$packageName", null) == null) {
                setDesignatedProfileForApp(packageName, profileName)
            }
        }
    }

    fun deleteProfile(profileName: String?) {
        DiagnosticLog.info("KeymapProfiles", "profile_delete_requested", "profile" to profileName, "exists" to profileExists(profileName))
        val editor = sharedPref.edit().remove(profileName)
        if (profileName != null) {
            if (sharedPref.getString(KEY_LAST_SELECTED_PROFILE, null) == profileName) {
                editor.remove(KEY_LAST_SELECTED_PROFILE)
            }
            sharedPref.all.forEach { (key, value) ->
                if (key.startsWith("designated_profile_") && value == profileName) {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }

    fun getProfile(profileName: String?, loadMacros: Boolean): KeymapProfile {
        val stringSet = sharedPref.getStringSet(profileName, null)
        if (!profileName.isNullOrEmpty() && stringSet == null) {
            DiagnosticLog.warn("KeymapProfiles", "profile_load_missing", "profile" to profileName, "loadMacros" to loadMacros)
        }
        val keymapProfile = getProfile(stringSet)
        if (loadMacros) keymapProfile.loadMacros(context)
        DiagnosticLog.debug("KeymapProfiles", "profile_loaded", "profile" to profileName, "package" to keymapProfile.packageName, "enabled" to !keymapProfile.disabled, "lineCount" to (stringSet?.size ?: 0), "macroCount" to keymapProfile.macroIdMap.size, "loadMacros" to loadMacros)
        return keymapProfile
    }

    fun getProfile(lines: Set<String>?): KeymapProfile = parse(lines)

    companion object {
        const val MOUSE_RIGHT = "MOUSE_RIGHT"
        const val KEY_LAST_SELECTED_PROFILE = "last_selected_profile"
        internal const val DEFAULT_PROFILE_NAME = "Default"

        fun parse(lines: Set<String>?): KeymapProfile {
            val profile = KeymapProfile()
            profile.disabled = true
            lines?.forEach { line ->

                val data = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray() // Split a String like KEY_G 760.86346 426.18607
                when (data[0]) {
                    MouseAimConfig.TAG ->
                        profile.mouseAimConfig = MouseAimConfig(data)

                    Dpad.TAG,
                    Dpad.TAG_ARROW_KEYS ->
                        if (data.size >= 12)
                            for (i in profile.dpadArray.indices)
                                if (profile.dpadArray[i] == null) {
                                    profile.dpadArray[i] = Dpad(data)
                                    break
                                }

                    MOUSE_RIGHT -> if (data.size >= 3) {
                        profile.rightClick = Key()
                        profile.rightClick!!.x = data[1].toFloat()
                        profile.rightClick!!.y = data[2].toFloat()
                    }

                    "APPLICATION" -> if (data.size >= 2)
                        profile.packageName = data[1]

                    "ENABLED" ->
                        profile.disabled = false

                    "SCREENSIZE" -> if (data.size >= 3) {
                        profile.xRes = data[1].toInt()
                        profile.yRes = data[2].toInt()
                    }

                    MacroIdUtils.TAG ->
                        if (data.size >= 3) {
                            val macroId = data[1]
                            val macro = Macro()
                            macro.triggerKey = data[2]
                            profile.macroIdMap[macroId] = macro
                        }

                    SwipeKey.TAG ->
                        if (data.size > 6) profile.swipeKeys.add(SwipeKey(data))

                    ShiftKey.TAG ->
                        if (ShiftKey.isValidData(data)) profile.shiftKeys.add(ShiftKey(data))

                    Camera.TAG ->
                        if (data.size >= 4)
                            profile.camera = Camera(data)

                    MouseWalk.TAG ->
                        if (data.size == 4)
                            profile.mouseWalk = MouseWalk(data)

                    else -> {
                        if (data.size == 4 && data[0].startsWith("KEY_")) {
                            val key = Key(data)
                            profile.keys.add(key)
                        }
                    }
                }
            }
            return profile
        }
    }
}
