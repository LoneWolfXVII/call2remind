package app.call2remind.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoreInfoTest {
    @Test
    fun name() {
        assertThat(CoreInfo.NAME).isEqualTo("call2remind-core")
    }
}
