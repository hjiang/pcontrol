package com.pcontrol.app.ui

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MaxWidthLinearLayoutTest {
    @Test
    fun capsContentWidthWithoutForcingCompactWindowsWider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layout = MaxWidthLinearLayout(context).apply {
            maxContentWidthPx = 200
            addView(View(context))
        }

        layout.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )

        assertEquals(200, layout.measuredWidth)
    }

    @Test
    fun fillsWindowWhenWindowIsNarrowerThanMaximum() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layout = MaxWidthLinearLayout(context).apply { maxContentWidthPx = 600 }

        layout.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )

        assertEquals(360, layout.measuredWidth)
    }
}
