package com.clusterrr.hexeditorwatchface;

import static com.clusterrr.hexeditorwatchface.SettingsActivity.PREF_VALUE_BACKGROUND_RANDOM;
import static com.clusterrr.hexeditorwatchface.SettingsActivity.PREF_VALUE_BACKGROUND_RANDOM_ONCE;
import static com.clusterrr.hexeditorwatchface.SettingsActivity.PREF_VALUE_BACKGROUND_ZEROS;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;

import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class HexWatchFace extends CanvasWatchFaceService {
    /*
     * Updates rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    public static String TAG = "hex_watchface";
    private static final long INTERACTIVE_UPDATE_RATE = TimeUnit.SECONDS.toMillis(1);
    private static final long MAX_HEART_RATE_AGE = TimeUnit.SECONDS.toMillis(15);
    private static final long TOUCH_TIME = TimeUnit.SECONDS.toMillis(3);
    private static final int NUMBER_WIDTH = 78;
    private static final int NUMBER_V_INTERVAL = 56;
    private static final int BACKGROUND_OFFSET = -54;
    private static final int ENDIANNESS_LITTLE_ENDIAN = 0;
    private static final int ENDIANNESS_BIG_ENDIAN = 1;
    private static final int ENDIANNESS_FAKE_HEX = 2;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<HexWatchFace.Engine> mWeakReference;

        public EngineHandler(HexWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            HexWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) engine.handleUpdateTimeMessage();
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements SensorEventListener {
        /* Handler to update the time once a second in interactive mode. */
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mAmbient;
        private boolean mRegisteredTimeZoneReceiver = false;
        private Bitmap mBackgroundBitmap;
        private Bitmap mVignettingBitmap;
        private Bitmap mBarsBitmap;
        private HexNumbers mNumbers;
        private int mHeartRate = 0;
        private long mHeartRateTS = 0;
        private int mStepCounter = -1;
        private long mTouchTS = 0;
        private SensorManager mSensorManager = null;
        private Sensor mHeartRateSensor = null;
        private Sensor mStepCountSensor = null;
        private int mBackgroundMinX = 0;
        private int mBackgroundMinY = 0;
        private int mBackgroundMaxX = 0;
        private int mBackgroundMaxY = 0;
        private int[] mBackground;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(HexWatchFace.this)
                    .setAcceptsTapEvents(true)
                    .build());

            mCalendar = Calendar.getInstance();
            Resources res = getResources();
            mBackgroundBitmap = BitmapFactory.decodeResource(res, R.drawable.bg_empty);
            mVignettingBitmap = BitmapFactory.decodeResource(res, R.drawable.vignetting);
            mBarsBitmap = BitmapFactory.decodeResource(res, R.drawable.bars);
            mNumbers = new HexNumbers(res);
            mSensorManager = ((SensorManager)getSystemService(SENSOR_SERVICE));
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;
            mTouchTS = 0;
            updateTimer();
        }

        /**
         * Captures tap event (and tap type). The {@link WatchFaceService#TAP_TYPE_TAP} case can be
         * used for implementing specific logic to handle the gesture.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    //Log.d(TAG, "Touched");
                    mTouchTS = System.currentTimeMillis();
                    invalidate();
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    //Log.d(TAG, "Touch cancel");
                    break;
                case TAP_TYPE_TAP:
                    //Log.d(TAG, "Tap");
                    break;
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Context context = getApplicationContext();
            SharedPreferences prefs = getApplicationContext().getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE);
            Resources res = getApplicationContext().getResources();
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            if (mBackgroundMinX == 0) mBackgroundMinX = -canvas.getWidth() / 2 / NUMBER_WIDTH;
            if (mBackgroundMaxX == 0) mBackgroundMaxX = canvas.getWidth() / 2 / NUMBER_WIDTH + 1;
            if (mBackgroundMinY == 0) mBackgroundMinY = -canvas.getWidth() / 2 / NUMBER_V_INTERVAL;
            if (mBackgroundMaxY == 0) mBackgroundMaxY = canvas.getWidth() / 2 / NUMBER_V_INTERVAL + 1;

            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
                if (mHeartRateSensor == null) {
                    mHeartRateSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
                    mSensorManager.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
                if ((mHeartRateTS + MAX_HEART_RATE_AGE < now) && (mHeartRate != 0)) {
                    mHeartRate = 0;
                    Log.d(TAG, "Heart rate is reset to 0");
                }
            }

            int todayStepStart = 0;
            int todaySteps = 0;
            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                if (mStepCountSensor == null) {
                    mStepCountSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
                    mSensorManager.registerListener(this, mStepCountSensor, SensorManager.SENSOR_DELAY_NORMAL);
                }

                todayStepStart = prefs.getInt(getString(R.string.pref_today_step_start), 0);
                if (mStepCounter >= 0 && (
                        (mCalendar.get(Calendar.DAY_OF_MONTH) != prefs.getInt(getString(R.string.pref_steps_day), 0))
                        || (mStepCounter < todayStepStart))
                    ) {
                    prefs.edit()
                            .putInt(getString(R.string.pref_steps_day), mCalendar.get(Calendar.DAY_OF_MONTH))
                            .putInt(getString(R.string.pref_today_step_start), mStepCounter)
                            .apply();
                    todayStepStart = mStepCounter;
                }
                todaySteps = Math.max(mStepCounter - todayStepStart, 0);
            }

            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryIntent = getApplicationContext().registerReceiver(null, filter);
            int battery = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);

            boolean tapped = mTouchTS + TOUCH_TIME >= now;

            int endianness =
                    (prefs.getInt(getString(R.string.pref_endianness), SettingsActivity.PREF_DEFAULT_ENDIANNESS)
                    == SettingsActivity.PREF_VALUE_ENDIANNESS_LITTLE_ENDIAN)
                        ? ENDIANNESS_LITTLE_ENDIAN
                        : ENDIANNESS_BIG_ENDIAN;

            int hour;
            if (prefs.getInt(getString(R.string.pref_time_format), SettingsActivity.PREF_DEFAULT_TIME_FORMAT)
                    == SettingsActivity.PREF_TIME_FORMAT_12)
                hour = mCalendar.get(Calendar.HOUR);
            else
                hour = mCalendar.get(Calendar.HOUR_OF_DAY);
            int timeSystem = prefs.getInt(getString(R.string.pref_time_system), SettingsActivity.PREF_DEFAULT_TIME_SYSTEM);
            int timeEndianness;
            switch (timeSystem) {
                default:
                case SettingsActivity.PREF_VALUE_TIME_DEC:
                    timeEndianness = ENDIANNESS_FAKE_HEX;
                    break;
                case SettingsActivity.PREF_VALUE_TIME_HEX:
                    timeEndianness = ENDIANNESS_LITTLE_ENDIAN;
                    break;
                case SettingsActivity.PREF_VALUE_TIME_DEC_ON_TAP:
                    timeEndianness = tapped ? ENDIANNESS_FAKE_HEX : ENDIANNESS_LITTLE_ENDIAN;
                    break;
            }

            if (!mAmbient) {
                canvas.drawBitmap(mBackgroundBitmap,
                        canvas.getWidth() / 2 - mBackgroundBitmap.getWidth() / 2,
                        canvas.getHeight() / 2 - mBackgroundBitmap.getHeight() / 2 + BACKGROUND_OFFSET,
                        null);

                int backgroundMode = prefs.getInt(getString(R.string.pref_background), SettingsActivity.PREF_DEFAULT_BACKGROUND);
                if (mBackground == null || prefs.getBoolean(getString(R.string.pref_background_redraw), false)) {
                    mBackground = new int[(mBackgroundMaxX - mBackgroundMinX + 1) * (mBackgroundMaxY - mBackgroundMinY + 1)];
                    switch (backgroundMode) {
                        case PREF_VALUE_BACKGROUND_RANDOM_ONCE:
                            for (int i = 0; i < mBackground.length; i++)
                                mBackground[i] = (int) (Math.random() * 256);
                            break;
                        case PREF_VALUE_BACKGROUND_ZEROS:
                            for (int i = 0; i < mBackground.length; i++)
                                mBackground[i] = 0;
                            break;
                    }
                    prefs.edit().putBoolean(getString(R.string.pref_background_redraw), false).apply();
                }
                if (backgroundMode == PREF_VALUE_BACKGROUND_RANDOM) {
                        for (int i = 0; i < mBackground.length; i++)
                            mBackground[i] = (int) (Math.random() * 256);
                }
                int p = 0;
                for (int x = mBackgroundMinX; x <= mBackgroundMaxX; x++) {
                    for (int y = mBackgroundMinY; y < mBackgroundMaxY; y++) {
                        drawNumberAtPos(canvas, mBackground[p++], HexNumbers.COLORS_CYAN, x, y);
                    }
                }

                drawNumber(canvas, hour, timeEndianness, 1, HexNumbers.COLORS_WHITE, 0, 0);
                drawNumber(canvas, mCalendar.get(Calendar.MINUTE), timeEndianness, 1,HexNumbers.COLORS_WHITE, 1, 0);
                drawNumber(canvas, mCalendar.get(Calendar.SECOND), timeEndianness, 1,HexNumbers.COLORS_CYAN, 2, 0);

                int dateSystem = prefs.getInt(getString(R.string.pref_date), SettingsActivity.PREF_DEFAULT_DATE);
                if (dateSystem != SettingsActivity.PREF_VALUE_NOT_SHOW) {
                    int dateEndianness;
                    if (dateSystem  == SettingsActivity.PREF_VALUE_COMMON_DEC_ON_TAP)
                        dateSystem = tapped
                                ? SettingsActivity.PREF_VALUE_COMMON_DEC
                                : SettingsActivity.PREF_VALUE_COMMON_HEX;
                    switch (dateSystem) {
                        default:
                        case SettingsActivity.PREF_VALUE_COMMON_DEC:
                            dateEndianness = ENDIANNESS_FAKE_HEX;
                            break;
                        case SettingsActivity.PREF_VALUE_COMMON_HEX:
                            dateEndianness = ENDIANNESS_LITTLE_ENDIAN;
                            break;
                    }
                    drawNumber(canvas, mCalendar.get(Calendar.DAY_OF_MONTH), dateEndianness, 1, HexNumbers.COLORS_CYAN, 2, -1);
                    drawNumber(canvas, mCalendar.get(Calendar.MONTH) + 1, dateEndianness, 1, HexNumbers.COLORS_CYAN, 2, 1);
                    drawNumber(canvas, mCalendar.get(Calendar.YEAR), dateEndianness, 1, HexNumbers.COLORS_CYAN, 1, -1);
                }

                int dayOfTheWeekMode = prefs.getInt(getString(R.string.pref_day_week), SettingsActivity.PREF_DEFAULT_DAY_OF_THE_WEEK);
                if (dayOfTheWeekMode != SettingsActivity.PREF_VALUE_NOT_SHOW) {
                    int dayOfTheWeek = mCalendar.get(Calendar.DAY_OF_WEEK) - 1;
                            if ((dayOfTheWeek == 0) && (dayOfTheWeekMode == SettingsActivity.PREF_VALUE_DAY_SUNDAY_7)) dayOfTheWeek = 7;
                    drawNumber(canvas, dayOfTheWeek, ENDIANNESS_FAKE_HEX, 1, HexNumbers.COLORS_CYAN, 1, 1);
                }

                int heartRateSystem = prefs.getInt(getString(R.string.pref_heart_rate), SettingsActivity.PREF_DEFAULT_HEART_RATE);
                if (heartRateSystem != SettingsActivity.PREF_VALUE_NOT_SHOW) {
                    if (heartRateSystem == SettingsActivity.PREF_VALUE_COMMON_DEC_ON_TAP)
                        heartRateSystem = tapped
                                ? SettingsActivity.PREF_VALUE_COMMON_DEC
                                : SettingsActivity.PREF_VALUE_COMMON_HEX;
                    switch (heartRateSystem) {
                        default:
                        case SettingsActivity.PREF_VALUE_COMMON_DEC:
                            drawNumber(canvas, mHeartRate, ENDIANNESS_FAKE_HEX, 2, HexNumbers.COLORS_CYAN, -1, -1);
                            break;
                        case SettingsActivity.PREF_VALUE_COMMON_HEX:
                            drawNumber(canvas, mHeartRate, ENDIANNESS_LITTLE_ENDIAN, 1, HexNumbers.COLORS_CYAN, -1, -1);
                            break;
                    }
                }

                int batterySystem = prefs.getInt(getString(R.string.pref_battery), SettingsActivity.PREF_DEFAULT_BATTERY);
                if (batterySystem != SettingsActivity.PREF_VALUE_NOT_SHOW) {
                    switch(batterySystem) {
                        case SettingsActivity.PREF_VALUE_BATTERY_HEX_0_FF_TAP:
                            batterySystem = tapped
                                    ? SettingsActivity.PREF_VALUE_BATTERY_DEC_0_100
                                    : SettingsActivity.PREF_VALUE_BATTERY_HEX_0_FF;
                            break;
                        case SettingsActivity.PREF_VALUE_BATTERY_HEX_0_64_TAP:
                            batterySystem = tapped
                                    ? SettingsActivity.PREF_VALUE_BATTERY_DEC_0_100
                                    : SettingsActivity.PREF_VALUE_BATTERY_HEX_0_64;
                            break;
                    }
                    switch (batterySystem) {
                        default:
                        case SettingsActivity.PREF_VALUE_BATTERY_DEC_0_100:
                            drawNumber(canvas, battery, ENDIANNESS_FAKE_HEX, 2, HexNumbers.COLORS_CYAN, -2, 0);
                            break;
                        case SettingsActivity.PREF_VALUE_BATTERY_HEX_0_64:
                            drawNumber(canvas, battery, ENDIANNESS_LITTLE_ENDIAN, 2, HexNumbers.COLORS_CYAN, -1, 0);
                            break;
                        case SettingsActivity.PREF_VALUE_BATTERY_HEX_0_FF:
                            drawNumber(canvas, battery * 255 / 100, ENDIANNESS_LITTLE_ENDIAN, 2, HexNumbers.COLORS_CYAN, -1, 0);
                            break;
                    }
                }

                int stepsSystem = prefs.getInt(getString(R.string.pref_steps), SettingsActivity.PREF_DEFAULT_STEPS);
                if (stepsSystem != SettingsActivity.PREF_VALUE_NOT_SHOW) {
                    if (stepsSystem == SettingsActivity.PREF_VALUE_COMMON_DEC_ON_TAP)
                        stepsSystem = tapped
                                ? SettingsActivity.PREF_VALUE_COMMON_DEC
                                : SettingsActivity.PREF_VALUE_COMMON_HEX;
                    switch (stepsSystem) {
                        default:
                        case SettingsActivity.PREF_VALUE_COMMON_DEC:
                            Log.d(TAG, "Steps total: "+mStepCounter+", steps today: " + todaySteps +", today start: " + todayStepStart);
                            drawNumber(canvas, todaySteps, ENDIANNESS_FAKE_HEX, 3, HexNumbers.COLORS_CYAN, -2, 1);
                            break;
                        case SettingsActivity.PREF_VALUE_COMMON_HEX:
                            drawNumber(canvas, todaySteps, endianness, 2, HexNumbers.COLORS_CYAN, -1, 1);
                            break;
                    }
                }

                if (prefs.getInt(getString(R.string.pref_bars), SettingsActivity.PREF_DEFAULT_BARS)
                        == SettingsActivity.PREF_VALUE_BARS_SHOW) {
                    canvas.drawBitmap(mBarsBitmap,
                            canvas.getWidth() / 2 - mBarsBitmap.getWidth() / 2,
                            canvas.getHeight() / 2 - mBarsBitmap.getHeight() / 2 + BACKGROUND_OFFSET,
                            null);
                }

                if (prefs.getInt(getString(R.string.pref_vignetting), res.getInteger(R.integer.default_vignetting))
                    == SettingsActivity.PREF_VALUE_VINGETTING_ENABLED) {
                    canvas.drawBitmap(mVignettingBitmap,
                            new Rect(0, 0, mVignettingBitmap.getWidth(), mVignettingBitmap.getHeight()),
                            new Rect(0, 0, canvas.getWidth(), canvas.getHeight()),
                            null);
                }
            } else {
                canvas.drawColor(Color.BLACK);
                drawNumber(canvas, hour, timeEndianness, 1, HexNumbers.COLORS_DARK, 0, 0);
                drawNumber(canvas, mCalendar.get(Calendar.MINUTE), timeEndianness, 1,HexNumbers.COLORS_DARK, 1, 0);
            }
        }


        private void drawNumber(Canvas canvas, int number, int endianness, int size, int numberColor,
                                   float left, float top)
        {
            for (int i = 0; i < size; i++) {
                int digit, base;
                switch (endianness)
                {
                    case ENDIANNESS_BIG_ENDIAN:
                        digit = (number >> i) & 0xFF;
                        break;
                    case ENDIANNESS_LITTLE_ENDIAN:
                        base = (int)Math.pow(256, (size - i - 1));
                        digit = (number / base) % 256;
                        break;
                    default:
                    case ENDIANNESS_FAKE_HEX:
                        base = (int)Math.pow(100, (size - i - 1));
                        digit = (number / base) % 100;
                        digit = (digit % 10) | ((digit / 10) * 16);
                }
                drawNumberAtPos(canvas, digit, numberColor, left + i, top);
            }
        }

        private void drawNumberAtPos(Canvas canvas, int number, int numberColor,
                                     float left, float top)
        {
            drawDigitAtPoint(canvas, number, numberColor,
                    canvas.getWidth()/2 + (left - 1) * NUMBER_WIDTH,
                    canvas.getHeight()/2 + top * NUMBER_V_INTERVAL - 24);
        }

        private void drawDigitAtPoint(Canvas canvas, int number, int numberColor,
                                      float left, float top)
        {
            canvas.drawBitmap(mNumbers.GetNumber(number, numberColor), left, top, null);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren"t visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            HexWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            HexWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE
                        - (timeMs % INTERACTIVE_UPDATE_RATE);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            //Log.d(TAG, "New sensor data: " + event.sensor.getType());
            switch (event.sensor.getType()) {
                case Sensor.TYPE_HEART_RATE:
                    if ((int)event.values[0] != 0) {
                        mHeartRate = (int) event.values[0];
                        mHeartRateTS = System.currentTimeMillis();
                        //Log.d(TAG, "Heart rate: " + mHeartRate);
                    }
                    break;
                case Sensor.TYPE_STEP_COUNTER:
                    mStepCounter = (int)event.values[0];
                    Log.d(TAG, "Steps: " + mStepCounter);
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    }
}