package com.hdapp.drivingscore

import android.graphics.drawable.VectorDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.hdapp.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 배지 벡터 32개가 모두 열리는지 (SVG에서 바꾼 경로 글자가 안드로이드에서 읽히는지) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BadgeDrawableTest {
    @Test
    fun `배지 그림 32개가 모두 열린다`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ids = R.drawable::class.java.fields.filter { it.name.startsWith("badge_") }
        assertTrue("배지 그림이 32개여야 해요 (지금 ${ids.size}개)", ids.size == 32)
        ids.forEach { f ->
            val d = ContextCompat.getDrawable(context, f.getInt(null))
            assertTrue("${f.name} 를 열 수 없어요", d is VectorDrawable && d.intrinsicWidth > 0)
        }
    }
}
