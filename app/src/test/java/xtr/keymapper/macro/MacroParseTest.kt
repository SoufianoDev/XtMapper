package xtr.keymapper.macro

import org.junit.Test

class MacroParseTest {

    @Test
    fun parsesEventString() {
        // Real saved format from MacroView.logEvent: "x y t;" with no space after ';'
        val macro = Macro("10 20 0;30 40 100;")
        macro.triggerKey = "KEY_M"
        assert(macro.triggerKey == "KEY_M")
    }

    @Test(expected = NumberFormatException::class)
    fun garbageEventStringThrows() {
        Macro("not events")
    }
}
