package xtr.keymapper

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {

    @Test
    fun obtainIndexDigitsAndLetters() {
        assertEquals(0, Utils.obtainIndex("KEY_0"))
        assertEquals(9, Utils.obtainIndex("KEY_9"))
        assertEquals(10, Utils.obtainIndex("KEY_A"))
        assertEquals(35, Utils.obtainIndex("KEY_Z"))
    }

    @Test
    fun obtainIndexRejectsNonContiguousAndInvalid() {
        assertEquals(-1, Utils.obtainIndex("KEY_10"))
        assertEquals(-1, Utils.obtainIndex("KEY_a"))
        assertEquals(-1, Utils.obtainIndex("KEY__"))
    }

    @Test
    fun obtainIndexEmptySuffixIsZero() {
        assertEquals(0, Utils.obtainIndex("KEY_"))
    }
}
