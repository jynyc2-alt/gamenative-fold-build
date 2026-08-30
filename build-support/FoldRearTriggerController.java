package app.gamenative.ui.screen.xserver;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.window.core.ExperimentalWindowApi;
import androidx.window.area.WindowArea;
import androidx.window.area.WindowAreaCapability;
import androidx.window.area.WindowAreaController;
import androidx.window.area.WindowAreaPresentationSessionCallback;
import androidx.window.area.WindowAreaSessionPresenter;
import androidx.window.area.WindowAreaToken;

import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.widget.InputControlsView;
import com.winlator.winhandler.WinHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Uses Jetpack WindowArea presentation to turn a foldable's cover display into
 * configurable LT/RT touch zones while the game stays on the inner display.
 */
@ExperimentalWindowApi
public final class FoldRearTriggerController {
    private static final int TRIGGER_LT = 0;
    private static final int TRIGGER_RT = 1;

    private final Activity activity;
    private final InputControlsView inputControlsView;
    private final WinHandler winHandler;
    private final WindowAreaController windowAreaController;
    private final Executor mainExecutor;

    @Nullable private WindowAreaToken rearToken;
    @Nullable private WindowAreaSessionPresenter activeSession;
    private boolean started;
    private boolean presentationRequested;

    private final Consumer<List<WindowArea>> areaListener = areas -> {
        WindowArea rear = null;
        for (WindowArea area : areas) {
            if (WindowArea.Type.TYPE_REAR_FACING.equals(area.getType())) {
                rear = area;
                break;
            }
        }

        if (rear == null) {
            rearToken = null;
            return;
        }

        rearToken = rear.getToken();
        WindowAreaCapability capability = rear.getCapability(
                WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA);
        WindowAreaCapability.Status status = capability.getStatus();

        if (WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE.equals(status)
                && activeSession == null
                && !presentationRequested) {
            requestPresentation();
        }
    };

    public FoldRearTriggerController(
            @NonNull Activity activity,
            @NonNull InputControlsView inputControlsView,
            @NonNull WinHandler winHandler
    ) {
        this.activity = activity;
        this.inputControlsView = inputControlsView;
        this.winHandler = winHandler;
        this.windowAreaController = WindowAreaController.getOrCreate();
        this.mainExecutor = activity.getMainExecutor();
    }

    public void start() {
        if (started) return;
        started = true;
        windowAreaController.addWindowAreasListener(mainExecutor, areaListener);
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            windowAreaController.removeWindowAreasListener(areaListener);
        } catch (Throwable ignored) {
        }
        releaseBothTriggers();
        if (activeSession != null) {
            try {
                activeSession.close();
            } catch (Throwable ignored) {
            }
            activeSession = null;
        }
        presentationRequested = false;
        rearToken = null;
    }

    private void requestPresentation() {
        WindowAreaToken token = rearToken;
        if (!started || token == null || presentationRequested || activeSession != null) return;

        presentationRequested = true;
        try {
            windowAreaController.presentContentOnWindowArea(
                    token,
                    activity,
                    mainExecutor,
                    new WindowAreaPresentationSessionCallback() {
                        @Override
                        public void onSessionStarted(@NonNull WindowAreaSessionPresenter session) {
                            activeSession = session;
                            presentationRequested = false;
                            session.setContentView(new TriggerPadView(session.getContext()));
                        }

                        @Override
                        public void onContainerVisibilityChanged(boolean isVisible) {
                            if (!isVisible) releaseBothTriggers();
                        }

                        @Override
                        public void onSessionEnded(@Nullable Throwable throwable) {
                            releaseBothTriggers();
                            activeSession = null;
                            presentationRequested = false;
                        }
                    }
            );
        } catch (Throwable ignored) {
            presentationRequested = false;
        }
    }

    private void setTrigger(int trigger, boolean pressed) {
        ControlsProfile profile = inputControlsView.getProfile();
        if (profile == null) return;

        GamepadState virtualState = profile.getGamepadState();
        int buttonIndex;
        if (trigger == TRIGGER_LT) {
            virtualState.triggerL = pressed ? 1.0f : 0.0f;
            buttonIndex = ExternalController.IDX_BUTTON_L2;
        } else {
            virtualState.triggerR = pressed ? 1.0f : 0.0f;
            buttonIndex = ExternalController.IDX_BUTTON_R2;
        }
        virtualState.setPressed(buttonIndex, pressed);

        ExternalController currentController = winHandler.getCurrentController();
        if (currentController != null) {
            if (trigger == TRIGGER_LT) {
                currentController.state.triggerL = pressed ? 1.0f : 0.0f;
            } else {
                currentController.state.triggerR = pressed ? 1.0f : 0.0f;
            }
            currentController.state.setPressed(buttonIndex, pressed);
        }

        winHandler.sendGamepadState();
        winHandler.sendVirtualGamepadState(virtualState);
    }

    private void releaseBothTriggers() {
        setTrigger(TRIGGER_LT, false);
        setTrigger(TRIGGER_RT, false);
    }

    private final class TriggerPadView extends View {
        private static final String PREFS = "fold_rear_trigger_layout_v1";
        private static final String KEY_LT = "lt";
        private static final String KEY_RT = "rt";
        private static final int EDIT_NONE = 0;
        private static final int EDIT_MOVE = 1;
        private static final int EDIT_RESIZE = 2;

        // Cover-screen perspective is mirrored relative to the inner display: RT defaults left,
        // LT defaults right. The editor can move either zone anywhere afterward.
        private final RectF defaultRt = new RectF(0.00f, 0.00f, 0.50f, 1.00f);
        private final RectF defaultLt = new RectF(0.50f, 0.00f, 1.00f, 1.00f);

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final Map<Integer, Integer> pointerTriggers = new HashMap<>();

        private RectF ltNorm;
        private RectF rtNorm;
        private RectF editSnapshotLt;
        private RectF editSnapshotRt;

        private final RectF editButtonPx = new RectF();
        private final RectF saveButtonPx = new RectF();
        private final RectF resetButtonPx = new RectF();
        private final RectF cancelButtonPx = new RectF();

        private int ltPointers;
        private int rtPointers;
        private boolean editMode;
        private int selectedTrigger = -1;
        private int editPointerId = -1;
        private int editAction = EDIT_NONE;
        private float editStartX;
        private float editStartY;
        private RectF editStartRect;

        TriggerPadView(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(10, 10, 10));
            setFocusable(true);
            setKeepScreenOn(true);
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            ltNorm = loadRect(KEY_LT, defaultLt);
            rtNorm = loadRect(KEY_RT, defaultRt);

            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(dp(3));
            outlinePaint.setColor(Color.WHITE);

            labelPaint.setColor(Color.WHITE);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            hintPaint.setColor(Color.LTGRAY);
            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            buttonPaint.setColor(Color.rgb(48, 48, 48));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            updateToolbarRects();
            drawTrigger(canvas, TRIGGER_RT, rtNorm, rtPointers > 0, selectedTrigger == TRIGGER_RT);
            drawTrigger(canvas, TRIGGER_LT, ltNorm, ltPointers > 0, selectedTrigger == TRIGGER_LT);

            if (editMode) {
                drawToolbarButton(canvas, saveButtonPx, "SAVE");
                drawToolbarButton(canvas, resetButtonPx, "RESET");
                drawToolbarButton(canvas, cancelButtonPx, "CANCEL");
                hintPaint.setTextSize(dp(13));
                canvas.drawText("Drag a trigger to move • drag its corner to resize",
                        getWidth() / 2f, getHeight() - dp(12), hintPaint);
            } else {
                drawToolbarButton(canvas, editButtonPx, "EDIT");
            }
        }

        private void drawTrigger(Canvas canvas, int trigger, RectF norm, boolean pressed, boolean selected) {
            RectF r = toPixels(norm);
            if (editMode) {
                fillPaint.setColor(trigger == TRIGGER_RT
                        ? Color.rgb(42, 48, 62)
                        : Color.rgb(55, 42, 62));
            } else {
                fillPaint.setColor(pressed ? Color.rgb(72, 72, 72) : Color.rgb(24, 24, 24));
            }
            canvas.drawRoundRect(r, dp(14), dp(14), fillPaint);

            if (editMode) {
                outlinePaint.setStrokeWidth(selected ? dp(5) : dp(3));
                outlinePaint.setColor(selected ? Color.WHITE : Color.rgb(150, 150, 150));
                canvas.drawRoundRect(r, dp(14), dp(14), outlinePaint);

                float handle = Math.min(dp(34), Math.min(r.width(), r.height()) * 0.22f);
                fillPaint.setColor(Color.rgb(210, 210, 210));
                canvas.drawRect(r.right - handle, r.bottom - handle, r.right, r.bottom, fillPaint);
            }

            float labelSize = Math.max(dp(24), Math.min(dp(54), Math.min(r.width(), r.height()) * 0.20f));
            float hintSize = Math.max(dp(12), labelSize * 0.34f);
            labelPaint.setTextSize(labelSize);
            hintPaint.setTextSize(hintSize);

            float cx = r.centerX();
            float cy = r.centerY();
            String label = trigger == TRIGGER_RT ? "RT / R2" : "LT / L2";
            canvas.drawText(label, cx, cy, labelPaint);
            if (!editMode) {
                canvas.drawText("HOLD", cx, cy + labelSize * 0.70f, hintPaint);
            }
        }

        private void drawToolbarButton(Canvas canvas, RectF r, String text) {
            buttonPaint.setColor(Color.rgb(45, 45, 45));
            canvas.drawRoundRect(r, dp(10), dp(10), buttonPaint);
            outlinePaint.setStrokeWidth(dp(2));
            outlinePaint.setColor(Color.rgb(175, 175, 175));
            canvas.drawRoundRect(r, dp(10), dp(10), outlinePaint);
            labelPaint.setTextSize(Math.max(dp(12), r.height() * 0.34f));
            canvas.drawText(text, r.centerX(), r.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            return editMode ? handleEditTouch(event) : handleNormalTouch(event);
        }

        private boolean handleNormalTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    float x = event.getX(index);
                    float y = event.getY(index);
                    updateToolbarRects();
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                            && pointerTriggers.isEmpty()
                            && editButtonPx.contains(x, y)) {
                        enterEditMode();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }
                    pressPointer(event, index);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    releasePointer(event.getPointerId(event.getActionIndex()));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    releaseAllPointers();
                    return true;
                default:
                    return true;
            }
        }

        private boolean handleEditTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = event.getX();
                    float y = event.getY();
                    updateToolbarRects();
                    if (saveButtonPx.contains(x, y)) {
                        saveLayout();
                        exitEditMode();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }
                    if (resetButtonPx.contains(x, y)) {
                        ltNorm = new RectF(defaultLt);
                        rtNorm = new RectF(defaultRt);
                        selectedTrigger = -1;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        invalidate();
                        return true;
                    }
                    if (cancelButtonPx.contains(x, y)) {
                        if (editSnapshotLt != null) ltNorm = new RectF(editSnapshotLt);
                        if (editSnapshotRt != null) rtNorm = new RectF(editSnapshotRt);
                        exitEditMode();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }
                    beginEditGesture(event);
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    updateEditGesture(event);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endEditGesture();
                    return true;
                default:
                    return true;
            }
        }

        private void enterEditMode() {
            releaseAllPointers();
            editSnapshotLt = new RectF(ltNorm);
            editSnapshotRt = new RectF(rtNorm);
            selectedTrigger = -1;
            editMode = true;
            invalidate();
        }

        private void exitEditMode() {
            endEditGesture();
            editMode = false;
            selectedTrigger = -1;
            editSnapshotLt = null;
            editSnapshotRt = null;
            invalidate();
        }

        private void beginEditGesture(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            int trigger = hitTrigger(x, y);
            if (trigger < 0) {
                selectedTrigger = -1;
                invalidate();
                return;
            }

            selectedTrigger = trigger;
            editPointerId = event.getPointerId(0);
            editStartX = x;
            editStartY = y;
            RectF current = trigger == TRIGGER_LT ? ltNorm : rtNorm;
            editStartRect = new RectF(current);
            RectF px = toPixels(current);
            float handle = Math.min(dp(42), Math.min(px.width(), px.height()) * 0.28f);
            editAction = (x >= px.right - handle && y >= px.bottom - handle)
                    ? EDIT_RESIZE
                    : EDIT_MOVE;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        }

        private void updateEditGesture(MotionEvent event) {
            if (editPointerId < 0 || editStartRect == null || selectedTrigger < 0) return;
            int index = event.findPointerIndex(editPointerId);
            if (index < 0 || getWidth() <= 0 || getHeight() <= 0) return;

            float dx = (event.getX(index) - editStartX) / getWidth();
            float dy = (event.getY(index) - editStartY) / getHeight();
            RectF next = new RectF(editStartRect);

            if (editAction == EDIT_MOVE) {
                float w = next.width();
                float h = next.height();
                float left = clamp(editStartRect.left + dx, 0f, 1f - w);
                float top = clamp(editStartRect.top + dy, 0f, 1f - h);
                next.set(left, top, left + w, top + h);
            } else if (editAction == EDIT_RESIZE) {
                float minW = Math.max(0.12f, dp(90) / Math.max(1f, getWidth()));
                float minH = Math.max(0.12f, dp(90) / Math.max(1f, getHeight()));
                next.right = clamp(editStartRect.right + dx, editStartRect.left + minW, 1f);
                next.bottom = clamp(editStartRect.bottom + dy, editStartRect.top + minH, 1f);
            }

            if (selectedTrigger == TRIGGER_LT) {
                ltNorm = next;
            } else {
                rtNorm = next;
            }
            invalidate();
        }

        private void endEditGesture() {
            editPointerId = -1;
            editAction = EDIT_NONE;
            editStartRect = null;
        }

        private void pressPointer(MotionEvent event, int pointerIndex) {
            int pointerId = event.getPointerId(pointerIndex);
            if (pointerTriggers.containsKey(pointerId)) return;

            int trigger = hitTrigger(event.getX(pointerIndex), event.getY(pointerIndex));
            if (trigger < 0) return;

            pointerTriggers.put(pointerId, trigger);
            if (trigger == TRIGGER_LT) {
                if (ltPointers++ == 0) setTrigger(TRIGGER_LT, true);
            } else {
                if (rtPointers++ == 0) setTrigger(TRIGGER_RT, true);
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
        }

        private void releasePointer(int pointerId) {
            Integer trigger = pointerTriggers.remove(pointerId);
            if (trigger == null) return;

            if (trigger == TRIGGER_LT) {
                ltPointers = Math.max(0, ltPointers - 1);
                if (ltPointers == 0) setTrigger(TRIGGER_LT, false);
            } else {
                rtPointers = Math.max(0, rtPointers - 1);
                if (rtPointers == 0) setTrigger(TRIGGER_RT, false);
            }
            invalidate();
        }

        private void releaseAllPointers() {
            pointerTriggers.clear();
            boolean hadLt = ltPointers > 0;
            boolean hadRt = rtPointers > 0;
            ltPointers = 0;
            rtPointers = 0;
            if (hadLt) setTrigger(TRIGGER_LT, false);
            if (hadRt) setTrigger(TRIGGER_RT, false);
            invalidate();
        }

        private int hitTrigger(float x, float y) {
            RectF lt = toPixels(ltNorm);
            RectF rt = toPixels(rtNorm);
            boolean inLt = lt.contains(x, y);
            boolean inRt = rt.contains(x, y);
            if (inLt && inRt) {
                return lt.width() * lt.height() <= rt.width() * rt.height() ? TRIGGER_LT : TRIGGER_RT;
            }
            if (inLt) return TRIGGER_LT;
            if (inRt) return TRIGGER_RT;
            return -1;
        }

        private void updateToolbarRects() {
            float margin = dp(10);
            float h = dp(42);
            if (!editMode) {
                float w = dp(86);
                editButtonPx.set(getWidth() - margin - w, margin, getWidth() - margin, margin + h);
                return;
            }

            float gap = dp(8);
            float available = Math.max(dp(180), getWidth() - margin * 2f - gap * 2f);
            float w = Math.min(dp(92), available / 3f);
            float total = w * 3f + gap * 2f;
            float left = (getWidth() - total) / 2f;
            saveButtonPx.set(left, margin, left + w, margin + h);
            resetButtonPx.set(left + w + gap, margin, left + w * 2f + gap, margin + h);
            cancelButtonPx.set(left + w * 2f + gap * 2f, margin, left + w * 3f + gap * 2f, margin + h);
        }

        private RectF loadRect(String key, RectF fallback) {
            if (!prefs.contains(key + "_l")) return new RectF(fallback);
            RectF r = new RectF(
                    prefs.getFloat(key + "_l", fallback.left),
                    prefs.getFloat(key + "_t", fallback.top),
                    prefs.getFloat(key + "_r", fallback.right),
                    prefs.getFloat(key + "_b", fallback.bottom));
            if (!isValid(r)) return new RectF(fallback);
            return r;
        }

        private void saveLayout() {
            SharedPreferences.Editor editor = prefs.edit();
            putRect(editor, KEY_LT, ltNorm);
            putRect(editor, KEY_RT, rtNorm);
            editor.apply();
        }

        private void putRect(SharedPreferences.Editor editor, String key, RectF r) {
            editor.putFloat(key + "_l", r.left);
            editor.putFloat(key + "_t", r.top);
            editor.putFloat(key + "_r", r.right);
            editor.putFloat(key + "_b", r.bottom);
        }

        private boolean isValid(RectF r) {
            return r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f
                    && r.width() >= 0.05f && r.height() >= 0.05f;
        }

        private RectF toPixels(RectF norm) {
            return new RectF(
                    norm.left * getWidth(),
                    norm.top * getHeight(),
                    norm.right * getWidth(),
                    norm.bottom * getHeight());
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
