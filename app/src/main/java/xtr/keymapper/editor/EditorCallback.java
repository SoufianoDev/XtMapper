package xtr.keymapper.editor;

/**
 * Callback when editor UI is hidden
 */
public interface EditorCallback {
    void onHideView();

    /**
     * @return true if getevent is running
     */
    boolean getEvent();

}
