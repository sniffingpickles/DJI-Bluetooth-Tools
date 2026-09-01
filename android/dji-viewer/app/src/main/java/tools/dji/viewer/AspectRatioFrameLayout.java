package tools.dji.viewer;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

final class AspectRatioFrameLayout extends FrameLayout {
    private static final float VIDEO_ASPECT = 16f / 9f;

    AspectRatioFrameLayout(Context context) {
        super(context);
    }

    AspectRatioFrameLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        int width = availableWidth;
        int height = Math.round(width / VIDEO_ASPECT);
        if (height > availableHeight) {
            height = availableHeight;
            width = Math.round(height * VIDEO_ASPECT);
        }
        int exactWidth = MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY);
        int exactHeight = MeasureSpec.makeMeasureSpec(Math.max(1, height), MeasureSpec.EXACTLY);
        super.onMeasure(exactWidth, exactHeight);
    }
}
