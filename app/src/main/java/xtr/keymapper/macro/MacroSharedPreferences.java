package xtr.keymapper.macro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;


public class MacroSharedPreferences {
    private SharedPreferences sharedPref;
    private SharedPreferences.Editor editor;

    public MacroSharedPreferences(Context context) {
        if (context != null) {
            sharedPref = context.getSharedPreferences("macros", Context.MODE_PRIVATE);
            editor = sharedPref.edit();
        }
    }

    /**
     * Adds a key-value pair to SharedPreferences
     * @param id Macro identifier string
     * @param value Content of macro
     */
    public void addMacro(String id, String value) {
        if (editor != null) {
            editor.putString(id, value);
            editor.apply();
        }
    }

    /**
     * Retrieves the value associated with a key
     * @param id Macro identifier string
     * @return Content of macro
     */
    public String getMacro(String id) {
        return sharedPref != null ? sharedPref.getString(id, null) : null;
    }

    /**
     * Retrieves all keys in SharedPreferences
     */
    public Set<String> getMacroIds() {
        return sharedPref != null ? sharedPref.getAll().keySet() : null;
    }

    /**
     * Retrieves all key-value pairs in SharedPreferences
     */
    public Map<String, ?> getAllMacros() {
        return sharedPref != null ? sharedPref.getAll() : null;
    }

    /**
     * Removes a key-value pair from SharedPreferences.
     * @param id Macro identifier string
     */
    public void removeMacro(String id) {
        if (editor != null) {
            editor.remove(id);
            editor.apply();
        }
    }

    /**
     * Clears all key-value pairs in SharedPreferences
     */
    public void clearAllMacros() {
        if (editor != null) {
            editor.clear();
            editor.apply();
        }
    }

    /**
     * Adds a new macro with the next available identifier string like "macro0", "macro1", etc.
     * @param value Content of macro
     */
    public void addMacroWithNextAvailableId(String value) {
        if (sharedPref == null || editor == null) return;

        int index = 0;
        String newKey;

        // Find the next available macro key
        do {
            newKey = "macro" + index;
            index++;
        } while (sharedPref.contains(newKey));

        // Store the new key-value pair
        editor.putString(newKey, value);
        editor.apply();
    }
}