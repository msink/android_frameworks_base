package android.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;

public class BrightnessPanel extends Handler
        implements DialogInterface.OnKeyListener, View.OnClickListener {
    private static final String TAG = "BrightnessPanel";
    private static boolean LOGD = true;

    private static final int MINIMUM_BACKLIGHT = 30;
    private static final int MAXIMUM_BACKLIGHT = 255;
    private static final int DEFAULT_PRESS = 5;
    private static final int MSG_BRIGHTNESS_CHANGE = 0;
    private static final int MSG_CLOSE_WINDOW = 1;

    private Context mContext;
    private AlertDialog dialog;
    private SeekBar mLevel;
    private final View mView;
    private ImageView addBtn;
    private ImageView subBtn;
    private int mOldBrightness;

    public BrightnessPanel(Context context) {
        mContext = context;
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        dialog = builder.create();
        LayoutInflater inflater = (LayoutInflater)
                    context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(com.android.internal.R.layout.brightness_adjust, null);
        mView = view;
        subBtn = (ImageView)view.findViewById(com.android.internal.R.id.brightness_lower);
        addBtn = (ImageView)view.findViewById(com.android.internal.R.id.brightness_raise);
        mLevel = (SeekBar)view.findViewById(com.android.internal.R.id.brightness_level);
        subBtn.setOnClickListener(this);
        addBtn.setOnClickListener(this);
        mLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                bolShow = true;
                setBrightness(progress + MINIMUM_BACKLIGHT);
            }
            public void onStartTrackingTouch(SeekBar p1) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
                Settings.System.putInt(mContext.getContentResolver(),
                                       "screen_brightness",
                                       mLevel.getProgress() + MINIMUM_BACKLIGHT);
            }
        });
        mLevel.setMax(225);
        dialog.setOnKeyListener(this);
    }

    private boolean bolShow = false;
    private Runnable closeDialogRunnable = new Runnable() {
        public void run() {
            if (bolShow) {
                bolShow = false;
                removeCallbacks(closeDialogRunnable);
                postDelayed(closeDialogRunnable, 3000);
            } else {
                removeMessages(1);
                obtainMessage(1).sendToTarget();
            }
        }
    };

    public void onClick(View v) {
        switch (v.getId()) {
        case com.android.internal.R.id.brightness_lower:
            subBrightness();
            break;
        case com.android.internal.R.id.brightness_raise:
            addBrightness();
            break;
        case com.android.internal.R.id.brightness_level:
            break;
        }
    }

    private void addBrightness() {
        int prg = mLevel.getProgress();
        if ((prg + DEFAULT_PRESS) >= mLevel.getMax()) {
            prg = mLevel.getMax();
        } else {
            prg += DEFAULT_PRESS;
        }
        mLevel.setProgress(prg);
    }

    private void subBrightness() {
        int progress = mLevel.getProgress();
        if (progress - DEFAULT_PRESS <= 0) {
            progress = 0;
        } else {
            progress -= DEFAULT_PRESS;
        }
        mLevel.setProgress(progress);
    }

    public void postBrightnessChanged() {
        if (hasMessages(0))
            return;
        obtainMessage(0).sendToTarget();
    }

    protected void onShowBrightnessChanged() {
        mLevel.setMax(225);
        try {
            mOldBrightness = Settings.System.getInt(mContext.getContentResolver(),
                                                    "screen_brightness");
        } catch (Settings.SettingNotFoundException snfe) {
            mOldBrightness = MAXIMUM_BACKLIGHT;
        }
        mLevel.setProgress(mOldBrightness - MINIMUM_BACKLIGHT);
        dialog.show();
        dialog.setContentView(mView);
        postDelayed(closeDialogRunnable, 3000);
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_BRIGHTNESS_CHANGE:
            onShowBrightnessChanged();
            break;
        case MSG_CLOSE_WINDOW:
            Settings.System.putInt(mContext.getContentResolver(),
                                   "screen_brightness",
                                   mLevel.getProgress() + MINIMUM_BACKLIGHT);
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            break;
        }
    }

    private void setBrightness(int brightness) {
        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                power.setBacklightBrightness(brightness);
            }
        } catch (RemoteException e) {
        }
    }

    public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == 0) {
            subBrightness();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == 0) {
            addBrightness();
            return true;
        }
        return false;
    }
}
