package app.gamenative.ui.screen.xserver;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * Uses Jetpack WindowArea presentation to turn a foldable's rear/cover display into
 * a dedicated LT/RT touch surface while the game remains on the primary display.
 */
@ExperimentalWindowApi
public final class FoldRearTriggerController {
    private static final int SIDE_LEFT = 0;
    private static final int SIDE_RIGHT = 1;

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

    private void setTrigger(int side, boolean pressed) {
        ControlsProfile profile = inputControlsView.getProfile();
        if (profile == null) return;

        GamepadState virtualState = profile.getGamepadState();
        int buttonIndex;
        if (side == SIDE_LEFT) {
            virtualState.triggerL = pressed ? 1.0f : 0.0f;
            buttonIndex = ExternalController.IDX_BUTTON_L2;
        } else {
            virtualState.triggerR = pressed ? 1.0f : 0.0f;
            buttonIndex = ExternalController.IDX_BUTTON_R2;
        }
        virtualState.setPressed(buttonIndex, pressed);

        // Mirror only the trigger into the current physical-controller state. This avoids
        // overwriting its live sticks/buttons with the virtual profile's other values.
        ExternalController currentController = winHandler.getCurrentController();
        if (currentController != null) {
            if (side == SIDE_LEFT) {
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
        setTrigger(SIDE_LEFT, false);
        setTrigger(SIDE_RIGHT, false);
    }

    private final class TriggerPadView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<Integer, Integer> pointerSides = new HashMap<>();
        private int leftPointers;
        private int rightPointers;

        TriggerPadView(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(12, 12, 12));
            setFocusable(true);
            setKeepScreenOn(true);

            dividerPaint.setColor(Color.rgb(90, 90, 90));
            dividerPaint.setStrokeWidth(dp(2));

            labelPaint.setColor(Color.WHITE);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            hintPaint.setColor(Color.LTGRAY);
            hintPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float half = getWidth() / 2f;

            fillPaint.setColor(leftPointers > 0 ? Color.rgb(65, 65, 65) : Color.rgb(22, 22, 22));
            canvas.drawRect(0, 0, half, getHeight(), fillPaint);
            fillPaint.setColor(rightPointers > 0 ? Color.rgb(65, 65, 65) : Color.rgb(22, 22, 22));
            canvas.drawRect(half, 0, getWidth(), getHeight(), fillPaint);
            canvas.drawLine(half, 0, half, getHeight(), dividerPaint);

            float labelSize = Math.max(dp(34), Math.min(getWidth(), getHeight()) * 0.10f);
            float hintSize = Math.max(dp(16), labelSize * 0.36f);
            labelPaint.setTextSize(labelSize);
            hintPaint.setTextSize(hintSize);

            float centerY = getHeight() / 2f;
            canvas.drawText("LT / L2", half / 2f, centerY, labelPaint);
            canvas.drawText("RT / R2", half + half / 2f, centerY, labelPaint);
            canvas.drawText("HOLD", half / 2f, centerY + labelSize * 0.72f, hintPaint);
            canvas.drawText("HOLD", half + half / 2f, centerY + labelSize * 0.72f, hintPaint);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    pressPointer(event, event.getActionIndex());
                    return true;

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

        private void pressPointer(MotionEvent event, int pointerIndex) {
            int pointerId = event.getPointerId(pointerIndex);
            if (pointerSides.containsKey(pointerId)) return;

            int side = event.getX(pointerIndex) < getWidth() / 2f ? SIDE_LEFT : SIDE_RIGHT;
            pointerSides.put(pointerId, side);
            if (side == SIDE_LEFT) {
                if (leftPointers++ == 0) setTrigger(SIDE_LEFT, true);
            } else {
                if (rightPointers++ == 0) setTrigger(SIDE_RIGHT, true);
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
        }

        private void releasePointer(int pointerId) {
            Integer side = pointerSides.remove(pointerId);
            if (side == null) return;

            if (side == SIDE_LEFT) {
                leftPointers = Math.max(0, leftPointers - 1);
                if (leftPointers == 0) setTrigger(SIDE_LEFT, false);
            } else {
                rightPointers = Math.max(0, rightPointers - 1);
                if (rightPointers == 0) setTrigger(SIDE_RIGHT, false);
            }
            invalidate();
        }

        private void releaseAllPointers() {
            pointerSides.clear();
            boolean hadLeft = leftPointers > 0;
            boolean hadRight = rightPointers > 0;
            leftPointers = 0;
            rightPointers = 0;
            if (hadLeft) setTrigger(SIDE_LEFT, false);
            if (hadRight) setTrigger(SIDE_RIGHT, false);
            invalidate();
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
