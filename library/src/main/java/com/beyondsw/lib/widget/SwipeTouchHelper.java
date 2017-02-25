package com.beyondsw.lib.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.OverScroller;

import com.beyondsw.lib.widget.rebound.SimpleSpringListener;
import com.beyondsw.lib.widget.rebound.Spring;
import com.beyondsw.lib.widget.rebound.SpringConfig;
import com.beyondsw.lib.widget.rebound.SpringListener;
import com.beyondsw.lib.widget.rebound.SpringSystem;


/**
 * Created by wensefu on 17-2-12.
 */
public class SwipeTouchHelper implements ISwipeTouchHelper, Handler.Callback {

    //// TODO: 2017/2/14
//    2，消失过程中改变alpha值
//    6，view缓存
    // 7,多点触控处理

    private static final String TAG = "StackCardsView-touch";

    private static final float SLOPE = 1.732f;
    private StackCardsView mSwipeView;
    private float mLastX;
    private float mLastY;
    private float mInitDownX;
    private float mInitDownY;
    private boolean mOnTouchableChild;
    private int mDragSlop;
    private int mMaxVelocity;
    private float mMinVelocity;
    private float mMinFastDisappearVelocity;
    private VelocityTracker mVelocityTracker;
    private boolean mIsBeingDragged;
    private int mDisappearedCnt;
    private int mDisappearingCnt;
    private View mTouchChild;
    private float mChildInitX;
    private float mChildInitY;
    private float mChildInitRotation;
    private float mAnimStartX;
    private float mAnimStartY;
    private float mAnimStartRotation;
    private SpringSystem mSpringSystem;
    private Spring mSpring;
    private OverScroller mScroller;

    private static final int MIN_FLING_VELOCITY = 400;

    private Handler mHandler;
    private static final int MSG_DO_DISAPPEAR_SCROLL = 1;

    public SwipeTouchHelper(StackCardsView view) {
        mSwipeView = view;
        final Context context = view.getContext();
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mDragSlop = (int) (configuration.getScaledTouchSlop() / mSwipeView.getDragSensitivity());
        mMaxVelocity = configuration.getScaledMaximumFlingVelocity();
        mMinVelocity = configuration.getScaledMinimumFlingVelocity();
        float density = context.getResources().getDisplayMetrics().density;
        mMinFastDisappearVelocity = (int) (MIN_FLING_VELOCITY * density);
        mSpringSystem = SpringSystem.create();
        mScroller = new OverScroller(context, new DecelerateInterpolator());
        mHandler = new Handler(this);
    }

    //cp from ViewDragHelper
    private static final Interpolator sInterpolator = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private SpringListener mSpringListener = new SimpleSpringListener() {
        @Override
        public void onSpringUpdate(Spring spring) {
            float value = (float) spring.getCurrentValue();
            mTouchChild.setX(mAnimStartX - (mAnimStartX - mChildInitX) * value);
            mTouchChild.setY(mAnimStartY - (mAnimStartY - mChildInitY) * value);
            mTouchChild.setRotation(mAnimStartRotation - (mAnimStartRotation - mChildInitRotation) * value);
            onCoverScrolled();
        }

        @Override
        public void onSpringAtRest(Spring spring) {
            super.onSpringAtRest(spring);
            mSwipeView.onCoverStatusChanged(isCoverIdle());
        }
    };

    @Override
    public boolean isCoverIdle() {
        boolean springIdle = (mSpring == null || mSpring.isAtRest());
        return springIdle && !mIsBeingDragged && (mDisappearingCnt == 0);
    }

    @Override
    public void onChildChanged() {
        mDisappearedCnt = 0;
        mDisappearingCnt = 0;
        updateTouchChild();
    }

    @Override
    public int getAdjustStartIndex() {
        return mDisappearedCnt + mDisappearingCnt;
    }

    private void updateTouchChild() {
        int index = mDisappearedCnt + mDisappearingCnt;
        log(TAG, "updateTouchChild index=" + index);
        mTouchChild = mSwipeView.getChildCount() > index ? mSwipeView.getChildAt(index) : null;
        if (mTouchChild != null) {
            mChildInitX = mTouchChild.getX();
            mChildInitY = mTouchChild.getY();
            mChildInitRotation = mTouchChild.getRotation();
        }
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = mSwipeView.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private static boolean isTouchOnView(View view, float x, float y) {
        if (view == null) {
            return false;
        }
        Rect rect = new Rect();
        view.getHitRect(rect);
        return rect.contains((int) x, (int) y);
    }

    private boolean isDirectionAllowDismiss() {
        int direction = mSwipeView.getDismissDirection();
        if (direction == StackCardsView.SWIPE_ALL) {
            return true;
        } else if (direction == 0) {
            return false;
        }
        float dx = mTouchChild.getX() - mChildInitX;
        float dy = mTouchChild.getY() - mChildInitY;
        //斜率小于SLOPE时，认为是水平滑动
        if (Math.abs(dx) * SLOPE > Math.abs(dy)) {
            if (dx > 0) {
                return (direction & StackCardsView.SWIPE_RIGHT) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_LEFT) != 0;
            }
        } else {
            if (dy > 0) {
                return (direction & StackCardsView.SWIPE_DOWN) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_UP) != 0;
            }
        }
    }

    private boolean isDistanceAllowDismiss() {
        if (mTouchChild == null) {
            return false;
        }
        float dx = mTouchChild.getX() - mChildInitX;
        float dy = mTouchChild.getY() - mChildInitY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        float dismiss_distance = mSwipeView.getDismissDistance();
        return distance >= dismiss_distance;
    }

    private boolean isVDirectionAllowDismiss(float vx, float vy) {
        int direction = mSwipeView.getDismissDirection();
        if (direction == StackCardsView.SWIPE_ALL) {
            return true;
        } else if (direction == 0) {
            return false;
        }
        //斜率小于SLOPE时，认为是水平滑动
        if (Math.abs(vx) * SLOPE > Math.abs(vy)) {
            if (vy > 0) {
                return (direction & StackCardsView.SWIPE_RIGHT) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_LEFT) != 0;
            }
        } else {
            if (vy > 0) {
                return (direction & StackCardsView.SWIPE_DOWN) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_UP) != 0;
            }
        }
    }

    private boolean canDrag(float dx, float dy) {
        int direction = mSwipeView.getSwipeDirection();
        if (direction == StackCardsView.SWIPE_ALL) {
            return true;
        } else if (direction == 0) {
            return false;
        }
        //斜率小于SLOPE时，认为是水平滑动
        if (Math.abs(dx) * SLOPE > Math.abs(dy)) {
            if (dx > 0) {
                return (direction & StackCardsView.SWIPE_RIGHT) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_LEFT) != 0;
            }
        } else {
            if (dy > 0) {
                return (direction & StackCardsView.SWIPE_DOWN) != 0;
            } else {
                return (direction & StackCardsView.SWIPE_UP) != 0;
            }
        }
    }

    private void performDrag(float dx, float dy) {
        if (mTouchChild == null) {
            return;
        }
        mTouchChild.setX(mTouchChild.getX() + dx);
        mTouchChild.setY(mTouchChild.getY() + dy);
        final float maxRotation = mSwipeView.getMaxRotation();
        float rotation = maxRotation * (mTouchChild.getX() - mChildInitX) / mSwipeView.getDismissDistance();
        if (rotation > maxRotation) {
            rotation = maxRotation;
        } else if (rotation < -maxRotation) {
            rotation = -maxRotation;
        }
        mSwipeView.getMaxRotation();
        mTouchChild.setRotation(rotation);
        onCoverScrolled();
    }

    private void animateToInitPos() {
        if (mTouchChild != null) {
            if (mSpring != null) {
                mSpring.removeAllListeners();
            }
            mAnimStartX = mTouchChild.getX();
            mAnimStartY = mTouchChild.getY();
            mAnimStartRotation = mTouchChild.getRotation();
            mSpring = mSpringSystem.createSpring();
            mSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 5));
            mSpring.addListener(mSpringListener);
            mSpring.setEndValue(1);
            mSwipeView.onCoverStatusChanged(false);
        }
    }

    private void animateToDisappear() {
        if (mTouchChild == null) {
            return;
        }
        final View disappearView = mTouchChild;
        final float initX = mChildInitX;
        final float initY = mChildInitY;
        mIsBeingDragged = false;
        mDisappearingCnt++;
        updateTouchChild();
        final float curX = disappearView.getX();
        final float curY = disappearView.getY();
        final float dx = curX - initX;
        final float dy = curY - initY;
        Rect rect = new Rect();
        disappearView.getHitRect(rect);
        String property;
        float target;
        int dir;
        long duration;
        float delta;
        if (Math.abs(dx) * SLOPE > Math.abs(dy)) {
            final int pWidth = mSwipeView.getWidth();
            property = "x";
            if (dx > 0) {
                delta = Math.max(pWidth - rect.left, 0);
                dir = StackCardsView.SWIPE_RIGHT;
            } else {
                delta = -Math.max(rect.right, 0);
                dir = StackCardsView.SWIPE_LEFT;
            }
            target = curX + delta;
            duration = computeSettleDuration((int) delta, 0, 0, 0);
        } else {
            final int pHeight = mSwipeView.getHeight();
            property = "y";
            if (dy > 0) {
                delta = Math.max(pHeight - rect.top, 0);
                dir = StackCardsView.SWIPE_DOWN;
            } else {
                delta = -Math.max(rect.bottom, 0);
                dir = StackCardsView.SWIPE_UP;
            }
            target = curY + delta;
            duration = computeSettleDuration(0, (int) delta, 0, 0);
        }
        log(TAG, "animateToDisappear,duration=" + duration);
        final int direction = dir;
        ObjectAnimator animator = ObjectAnimator.ofFloat(disappearView, property, target).setDuration(duration);
        animator.setInterpolator(sInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeView.onCardDismissed(direction);
                mDisappearedCnt++;
                mDisappearingCnt--;
                mSwipeView.onCoverStatusChanged(isCoverIdle());
            }

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mSwipeView.onCoverStatusChanged(false);
            }
        });
        animator.start();
    }

    private int[] calcScrollDistance(View view, float vx, float vy, float dx, float dy) {
        int[] result = new int[2];
        float edgeDeltaX = 0;
        float edgeDeltaY = 0;
        Rect rect = new Rect();
        view.getHitRect(rect);
        if (vx > 0) {
            edgeDeltaX = Math.max(0, mSwipeView.getWidth() - rect.left);
        } else if (vx < 0) {
            edgeDeltaX = Math.max(0, rect.right);
        }
        if (vy > 0) {
            edgeDeltaY = Math.max(0, mSwipeView.getHeight() - rect.top);
        } else if (vy < 0) {
            edgeDeltaY = Math.max(0, rect.bottom);
        }
        float scrollDx;
        float scrollDy;
        if (edgeDeltaX * Math.abs(dy) >= edgeDeltaY * Math.abs(dx)) {
            scrollDy = vy > 0 ? edgeDeltaY : -edgeDeltaY;
            float value = Math.abs(scrollDy * dx / dy);
            scrollDx = vx > 0 ? value : -value;
        } else {
            scrollDx = vx > 0 ? edgeDeltaX : -edgeDeltaX;
            float value = Math.abs(scrollDx * dy / dx);
            scrollDy = vy > 0 ? value : -value;
        }
        result[0] = (int) scrollDx;
        result[1] = (int) scrollDy;
        return result;
    }

    private boolean doFastDisappear(float vx, float vy) {
        if (mTouchChild == null) {
            return false;
        }
        if (Math.abs(vx) < mMinFastDisappearVelocity && Math.abs(vy) < mMinFastDisappearVelocity) {
            return false;
        }
        final View disappearView = mTouchChild;
        final float initX = mChildInitX;
        final float initY = mChildInitY;
        mDisappearingCnt++;
        updateTouchChild();
        float dx = disappearView.getX() - initX;
        float dy = disappearView.getY() - initY;
        int[] fdxArray = calcScrollDistance(disappearView, vx, vy, dx, dy);
        mScroller.startScroll((int) disappearView.getX(), (int) disappearView.getY(), fdxArray[0], fdxArray[1], 260);
        mHandler.obtainMessage(MSG_DO_DISAPPEAR_SCROLL, disappearView).sendToTarget();
        return true;
    }

    private int clampMag(int value, int absMin, int absMax) {
        final int absValue = Math.abs(value);
        if (absValue < absMin) return 0;
        if (absValue > absMax) return value > 0 ? absMax : -absMax;
        return value;
    }

    //cp from ViewDragHelper
    private int computeSettleDuration(int dx, int dy, int xvel, int yvel) {
        xvel = clampMag(xvel, (int) mMinVelocity, mMaxVelocity);
        yvel = clampMag(yvel, (int) mMinVelocity, mMaxVelocity);
        final int absDx = Math.abs(dx);
        final int absDy = Math.abs(dy);
        final int absXVel = Math.abs(xvel);
        final int absYVel = Math.abs(yvel);
        final int addedVel = absXVel + absYVel;
        final int addedDistance = absDx + absDy;

        final float xweight = xvel != 0 ? (float) absXVel / addedVel :
                (float) absDx / addedDistance;
        final float yweight = yvel != 0 ? (float) absYVel / addedVel :
                (float) absDy / addedDistance;

        int xduration = computeAxisDuration(dx, xvel, 1024);
        int yduration = computeAxisDuration(dy, yvel, 1024);

        return (int) (xduration * xweight + yduration * yweight);
    }

    //cp from ViewDragHelper
    private int computeAxisDuration(int delta, int velocity, int motionRange) {
        if (delta == 0) {
            return 0;
        }

        final int width = mSwipeView.getWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, (float) Math.abs(delta) / width);
        final float distance = halfWidth + halfWidth
                * distanceInfluenceForSnapDuration(distanceRatio);

        int duration;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float range = (float) Math.abs(delta) / motionRange;
            duration = (int) ((range + 1) * 256);
        }
        return Math.min(duration, 600);
    }

    //cp from ViewDragHelper
    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    private void onCoverScrolled() {
        if (mTouchChild == null) {
            return;
        }
        float dx = mTouchChild.getX() - mChildInitX;
        float dy = mTouchChild.getY() - mChildInitY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        float dismiss_distance = mSwipeView.getDismissDistance();
        if (distance >= dismiss_distance) {
            mSwipeView.onCoverScrolled(1);
        } else {
            mSwipeView.onCoverScrolled((float) distance / dismiss_distance);
        }
    }

    private void cancelSpringIfNeeded() {
        if (mSpring != null && !mSpring.isAtRest()) {
            mSpring.setAtRest();
            mSpring.removeAllListeners();
        }
    }

    private void clearVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what == MSG_DO_DISAPPEAR_SCROLL) {
            if (mScroller.computeScrollOffset()) {
                View view = (View) msg.obj;
                view.setX(mScroller.getCurrX());
                view.setY(mScroller.getCurrY());
                onCoverScrolled();
                Message m = mHandler.obtainMessage(MSG_DO_DISAPPEAR_SCROLL, view);
                mHandler.sendMessageDelayed(m, 15);
            } else {
                mSwipeView.onCardDismissed(0); //// TODO: 17-2-23
                mDisappearingCnt--;
                mDisappearedCnt++;
                mSwipeView.onCoverStatusChanged(isCoverIdle());
                mSwipeView.adjustChildren();
            }
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mTouchChild == null) {
            logw(TAG, "onInterceptTouchEvent,mTouchChild == null");
            return false;
        }
        final View touchChild = mTouchChild;
        final int action = ev.getAction() & MotionEvent.ACTION_MASK;
        log(TAG, "onInterceptTouchEvent action=" + action);
        if (action == MotionEvent.ACTION_DOWN) {
            clearVelocityTracker();
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mIsBeingDragged = false;
            return false;
        }
        if (mIsBeingDragged && action != MotionEvent.ACTION_DOWN) {
            return true;
        }
        final float x = ev.getX();
        final float y = ev.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!(mOnTouchableChild = isTouchOnView(touchChild, x, y))) {
                    return false;
                }
                requestParentDisallowInterceptTouchEvent(true);
                mInitDownX = mLastX = x;
                mInitDownY = mLastY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = x - mInitDownX;
                float dy = y - mInitDownY;
                mLastX = x;
                mLastY = y;
                if (Math.sqrt(dx * dx + dy * dy) > mDragSlop) {
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                break;
            case MotionEvent.ACTION_POINTER_UP:
                break;
        }
        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction() & MotionEvent.ACTION_MASK;
        log(TAG, "onTouchEvent action=" + action + ",mOnTouchableChild=" + mOnTouchableChild);
        if (mTouchChild == null) {
            return false;
        }
        float x = ev.getX();
        float y = ev.getY();
        mVelocityTracker.addMovement(ev);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!mOnTouchableChild) {
                    return false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                //子view未消费down事件时，mIsBeingDragged可能为false
                float dx = x - mLastX;
                float dy = y - mLastY;
                if (!canDrag(dx, dy)) {
                    return false;
                }
                if (!mIsBeingDragged) {
                    cancelSpringIfNeeded();
                    mIsBeingDragged = true;
                }
                performDrag(x - mLastX, y - mLastY);
                mLastX = x;
                mLastY = y;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (!mIsBeingDragged) {
                    break;
                }
                if (isDistanceAllowDismiss()) {
                    if (isDirectionAllowDismiss()) {
                        animateToDisappear();
                    } else {
                        animateToInitPos();
                    }
                } else {
                    if (mSwipeView.isFastSwipeAllowed()) {
                        final VelocityTracker velocityTracker = mVelocityTracker;
                        velocityTracker.computeCurrentVelocity(1000, mMaxVelocity);
                        float xv = mVelocityTracker.getXVelocity();
                        float yv = mVelocityTracker.getYVelocity();
                        if (!isVDirectionAllowDismiss(xv, yv) || !doFastDisappear(xv, yv)) {
                            animateToInitPos();
                        }
                    } else {
                        animateToInitPos();
                    }
                }
                mIsBeingDragged = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                break;
        }
        return true;
    }

    private static void log(String tag, String msg) {
        if (StackCardsView.DEBUG) {
            Log.d(tag, msg);
        }
    }

    private static void logw(String tag, String msg) {
        if (StackCardsView.DEBUG) {
            Log.w(tag, msg);
        }
    }
}
