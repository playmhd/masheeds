package com.masheeds.player;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.view.DragEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ListView;

import java.util.function.BiConsumer;

// The caller supplies the adapter and applies each completed move.
@SuppressLint("ViewConstructor")
final class PlaylistReorderList extends ListView {
    private final BiConsumer<Integer, Integer> onMove;
    private final ValueAnimator previewAnimator = new ValueAnimator();
    private int draggedPosition = INVALID_POSITION;
    private int draggedHeight;
    private int hovered = INVALID_POSITION;
    private float previewPosition;
    private int scrollDirection;
    private float dragY;

    private final Runnable scrollWhileDragging = new Runnable() {
        @Override public void run() {
            if (draggedPosition != INVALID_POSITION && scrollDirection != 0 && canScrollList(scrollDirection)) {
                scrollListBy(scrollDirection * dp(12));
                setHovered(positionAt(dragY));
                postDelayed(this, 32);
            }
        }
    };

    PlaylistReorderList(Context context, BiConsumer<Integer, Integer> onMove) {
        super(context);
        this.onMove = onMove;
        previewAnimator.setDuration(150);
        previewAnimator.setInterpolator(new DecelerateInterpolator());
        previewAnimator.addUpdateListener(animation -> {
            previewPosition = (float) animation.getAnimatedValue();
            invalidate();
        });
        setOnDragListener((view, event) -> handleDrag(event));
    }

    boolean beginDrag(View row, View handle) {
        int position = getPositionForView(row);
        if (draggedPosition != INVALID_POSITION || position == INVALID_POSITION) {
            return false;
        }
        draggedPosition = position;
        draggedHeight = row.getHeight() + getDividerHeight();
        previewPosition = position;
        hovered = position;
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row) {
            @Override public void onProvideShadowMetrics(Point size, Point touch) {
                super.onProvideShadowMetrics(size, touch);
                if (handle != null) {
                    touch.set(handle.getLeft() + handle.getWidth() / 2, handle.getTop() + handle.getHeight() / 2);
                }
            }
        };
        if (row.startDragAndDrop(ClipData.newPlainText("", ""), shadow, this, 0)) {
            invalidate();
            return true;
        }
        resetDrag();
        return false;
    }

    void resetDrag() {
        if (draggedPosition == INVALID_POSITION) {
            return;
        }
        previewAnimator.cancel();
        draggedPosition = INVALID_POSITION;
        hovered = INVALID_POSITION;
        setScrollDirection(0);
        invalidate();
    }

    private boolean handleDrag(DragEvent event) {
        if (event.getLocalState() != this || draggedPosition == INVALID_POSITION) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION -> {
                dragY = event.getY();
                setHovered(positionAt(dragY));
                int edge = dp(36);
                setScrollDirection(dragY < edge ? -1 : dragY > getHeight() - edge ? 1 : 0);
            }
            case DragEvent.ACTION_DRAG_EXITED -> {
                setScrollDirection(0);
                setHovered(INVALID_POSITION);
            }
            case DragEvent.ACTION_DROP -> {
                int target = positionAt(event.getY());
                int source = draggedPosition;
                resetDrag();
                if (target == INVALID_POSITION) {
                    return false;
                }
                onMove.accept(source, target);
            }
            case DragEvent.ACTION_DRAG_ENDED -> resetDrag();
        }
        return true;
    }

    private int positionAt(float y) {
        int first = getFirstVisiblePosition();
        for (int index = 0; index < getChildCount(); index++) {
            View row = getChildAt(index);
            int position = first + index;
            if (y < row.getTop() + row.getHeight() / 2f) {
                return position > draggedPosition ? Math.max(first, position - 1) : position;
            }
        }
        return getLastVisiblePosition();
    }

    private void setScrollDirection(int direction) {
        if (scrollDirection != direction) {
            scrollDirection = direction;
            removeCallbacks(scrollWhileDragging);
            if (direction != 0) {
                post(scrollWhileDragging);
            }
        }
    }

    private void setHovered(int position) {
        if (position != hovered) {
            hovered = position;
            previewAnimator.cancel();
            int target = position == INVALID_POSITION ? draggedPosition : position;
            previewAnimator.setFloatValues(previewPosition, target);
            previewAnimator.start();
        }
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (draggedPosition == INVALID_POSITION) {
            return super.drawChild(canvas, child, drawingTime);
        }
        int position = getFirstVisiblePosition() + indexOfChild(child);
        if (position == draggedPosition) {
            return false;
        }
        // Translate the drawing while keeping the real list geometry stable for scrolling and drop targets.
        boolean aboveSource = position < draggedPosition;
        float fraction = aboveSource ? position + 1 - previewPosition : previewPosition - position + 1;
        float shift = Math.max(0f, Math.min(1f, fraction)) * draggedHeight;
        if (shift == 0f) {
            return super.drawChild(canvas, child, drawingTime);
        }
        int saved = canvas.save();
        canvas.translate(0, aboveSource ? shift : -shift);
        boolean animating = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(saved);
        return animating;
    }

    @Override protected void onDetachedFromWindow() {
        resetDrag();
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
