package com.pcontrol.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.pcontrol.app.R
import kotlin.math.min

/**
 * A single-column layout that fills compact windows but caps its own measured
 * width in wide windows. Its parent may center it with `layout_gravity`.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    var maxContentWidthPx: Int = 0

    init {
        val attributes = context.obtainStyledAttributes(
            attrs,
            R.styleable.MaxWidthLinearLayout,
            defStyleAttr,
            0,
        )
        maxContentWidthPx = attributes.getDimensionPixelSize(
            R.styleable.MaxWidthLinearLayout_maxContentWidth,
            0,
        )
        attributes.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val cappedWidthSpec = if (maxContentWidthPx > 0 && mode != View.MeasureSpec.UNSPECIFIED) {
            View.MeasureSpec.makeMeasureSpec(min(availableWidth, maxContentWidthPx), mode)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(cappedWidthSpec, heightMeasureSpec)
    }
}
