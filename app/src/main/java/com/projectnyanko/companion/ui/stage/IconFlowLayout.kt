package com.projectnyanko.companion.ui.stage

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isGone

/**
 * Lays children out left to right and wraps to the next line when the width runs out.
 * Used for the enemy icon strip so the portraits read as a compact grid instead of a list.
 */
class IconFlowLayout(context: Context) : ViewGroup(context) {
    var horizontalGap = 0
    var verticalGap = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(minimumWidth)
        var lineWidth = 0
        var lineHeight = 0
        var contentHeight = 0
        var contentWidth = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val gap = if (lineWidth == 0) 0 else horizontalGap
            if (lineWidth > 0 && lineWidth + gap + childWidth > available) {
                contentHeight += lineHeight + verticalGap
                contentWidth = maxOf(contentWidth, lineWidth)
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += gap + childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
        contentWidth = maxOf(contentWidth, lineWidth)
        contentHeight += lineHeight

        // Report the full available width unless the parent demands an exact size. Measure and
        // layout must agree on the wrap width, otherwise rows re-flow and spill past the height
        // that was measured for them.
        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> available + paddingLeft + paddingRight
        }
        setMeasuredDimension(
            measuredWidth,
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(minimumWidth)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val gap = if (x == paddingLeft) 0 else horizontalGap
            if (x > paddingLeft && x + gap + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight + verticalGap
                lineHeight = 0
            } else {
                x += gap
            }
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
