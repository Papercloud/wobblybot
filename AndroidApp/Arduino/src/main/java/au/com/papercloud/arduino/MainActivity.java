package au.com.papercloud.arduino;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

import android.widget.SeekBar;

public class MainActivity extends FragmentActivity implements ContinuousDictationFragment.ContinuousDictationFragmentResultsCallback, TextToSpeech.OnInitListener, SensorEventListener
{
    private TextToSpeech tts;

    private static final String TAG = "Arduino"; // TAG is used to debug in Android logcat console
    private static final String ACTION_USB_PERMISSION = "au.com.papercloud.arduino.USB_PERMISSION";

    UsbAccessory mAccessory;
    ParcelFileDescriptor mFileDescriptor;
    FileInputStream mInputStream;
    FileOutputStream mOutputStream;
    private UsbManager mUsbManager;
    private PendingIntent mPermissionIntent;
    private boolean mPermissionRequestPending;
    TextView connectionStatus;
    ConnectedThread mConnectedThread;

    private SensorManager mSensorManager;
    private Sensor mRotVectSensor;
    private float[] orientationVals=new float[3];
    private float[] mRotationMatrix=new float[16];
    private TextView textView_Current_Angle;
    private TextView textView_Tilt_adjuster;
    private TextView textView_kP_adjuster;
    private TextView textView_kI_adjuster;
    private TextView textView_kD_adjuster;
    private TextView textView_multiplierAdjusterValue;
    private SeekBar seekBar_Tilt_adjuster;
    private SeekBar seekBar_kP_adjuster;
    private SeekBar seekBar_kI_adjuster;
    private SeekBar seekBar_kD_adjuster;
    private SeekBar seekBar_multiplierAdjuster;

    private Timer mCallBalancerTimer;
    private Balancer mBalancer;

    private SharedPreferences preferences;

    ContinuousDictationFragment dictationFragment;

    Button forwardButton, backButton, rightButton, stopButton, leftButton;

    @Override
    public void onDictationStart()
    {

    }

    @Override
    public void onResults(ContinuousDictationFragment delegate, ArrayList<String> dictationResults)
    {
        TextView speechResults = (TextView) findViewById(R.id.speechResults);
        String results = "[";
        for (String result : dictationResults)
        {
            results += result + ", ";
        }
        results += "]";

        speechResults.setText(results);

        for (String result : dictationResults)
        {
            String lower = result.toLowerCase();
            if (lower.contains("stop"))
            {
                tts.speak("Okay, I'm stopping.", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.STOP);
                return;
            }
            else if (lower.contains("forward"))
            {
                tts.speak("Onwards!", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.FORWARDS);
                return;
            }
            else if (lower.contains("back"))
            {
                tts.speak("Retreat! retreat!", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.BACKWARDS);
                return;
            }
            else if (lower.contains("left"))
            {
                tts.speak("To the left, to the left.", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.LEFT);
                return;
            }
            else if (lower.contains("right"))
            {
                tts.speak("Right we go!", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.RIGHT);
                return;
            }
            else if (lower.contains("move"))
            {
                tts.speak("I like to move it move it!", TextToSpeech.QUEUE_FLUSH, null);
                sendDirection(Direction.FORWARDS);
                return;
            }
        }
    }

    @Override
    public void onDictationFinish()
    {

    }

    @Override
    public void onInit(int code)
    {
        if (code == TextToSpeech.SUCCESS)
        {
            tts.setLanguage(Locale.getDefault());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener()
            {
                @Override
                public void onStart(String utteranceId)
                {
                    stopListening();
                }

                @Override
                public void onDone(String utteranceId)
                {
                    startListening();
                }

                @Override
                public void onError(String utteranceId)
                {
                    startListening();
                }
            });
        }
        else
        {
            tts = null;
            Toast.makeText(this, "Failed to initialize TTS engine.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopListening()
    {
        dictationFragment.stopVoiceRecognition();
    }

    private void startListening()
    {
        dictationFragment.startVoiceRecognitionCycle();
    }

    enum Direction {
        FORWARDS, BACKWARDS, LEFT, RIGHT, STOP
    }

    private class DirectionButtonTouchListener implements View.OnTouchListener
    {
        Direction down, up;

        DirectionButtonTouchListener(Direction down, Direction up)
        {
            this.down = down;
            this.up = up;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event)
        {
            if (event.getAction() == MotionEvent.ACTION_DOWN)
            {
                sendDirection(this.down);
            }
            else if (event.getAction() == MotionEvent.ACTION_UP)
            {
                sendDirection(this.up);
            }

            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Balancing tweak controls and debug
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mRotVectSensor=mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        textView_Current_Angle = (TextView) findViewById(R.id.TextView_CurrentAngle_Value);

        textView_Tilt_adjuster = (TextView) findViewById(R.id.TextView_Tilt_adjusterValue);
        seekBar_Tilt_adjuster = (SeekBar) findViewById(R.id.SeekBar_Tilt_adjuster);

        textView_kP_adjuster = (TextView) findViewById(R.id.TextView_kP_adjusterValue);
        seekBar_kP_adjuster = (SeekBar) findViewById(R.id.SeekBar_kP_adjuster);

        textView_kI_adjuster = (TextView) findViewById(R.id.TextView_kI_adjusterValue);
        seekBar_kI_adjuster = (SeekBar) findViewById(R.id.SeekBar_kI_adjuster);

        textView_kD_adjuster = (TextView) findViewById(R.id.TextView_kD_adjusterValue);
        seekBar_kD_adjuster = (SeekBar) findViewById(R.id.SeekBar_kD_adjuster);

        textView_multiplierAdjusterValue = (TextView) findViewById(R.id.TextView_multiplierAdjusterValue);
        seekBar_multiplierAdjuster = (SeekBar) findViewById(R.id.SeekBar_multiplierAdjuster);

        preferences = this.getSharedPreferences("PID.preferences.arduino", Context.MODE_PRIVATE);
        seekBar_Tilt_adjuster.setProgress(preferences.getInt("seekBar_Tilt_adjuster", 500));
        seekBar_kP_adjuster.setProgress(preferences.getInt("seekBar_kP_adjuster", 100));
        seekBar_kI_adjuster.setProgress(preferences.getInt("seekBar_kI_adjuster", 100));
        seekBar_kD_adjuster.setProgress(preferences.getInt("seekBar_kD_adjuster", 100));
        seekBar_multiplierAdjuster.setProgress(preferences.getInt("seekBar_multiplierAdjuster", 100));

        // This does the actual balancing.
        mBalancer = new Balancer();

        mCallBalancerTimer = new Timer();
        mCallBalancerTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mBalancer.balance();
            }
        }, 0, 100); // TODO: Make it more frequent than every second.

        connectionStatus = (TextView) findViewById(R.id.connectionStatus);

        forwardButton = (Button) findViewById(R.id.forward_button);
        backButton = (Button) findViewById(R.id.back_button);
        rightButton = (Button) findViewById(R.id.right_button);
        leftButton = (Button) findViewById(R.id.left_button);
        stopButton = (Button) findViewById(R.id.stop_button);

        forwardButton.setOnTouchListener(new DirectionButtonTouchListener(Direction.FORWARDS, Direction.STOP));
        backButton.setOnTouchListener(new DirectionButtonTouchListener(Direction.BACKWARDS, Direction.STOP));
        rightButton.setOnTouchListener(new DirectionButtonTouchListener(Direction.RIGHT, Direction.STOP));
        leftButton.setOnTouchListener(new DirectionButtonTouchListener(Direction.LEFT, Direction.STOP));
        stopButton.setOnTouchListener(new DirectionButtonTouchListener(Direction.STOP, Direction.STOP));

        mUsbManager = UsbManager.getInstance(this);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);

        tts = new TextToSpeech(this, this);

        dictationFragment = (ContinuousDictationFragment) getSupportFragmentManager().findFragmentById(R.id.dictation_fragment);
    }

    private void setText_current_angle(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_Current_Angle.setText(str);
            }
        });
    }

    private void setText_tilt_adjuster(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_Tilt_adjuster.setText(str);
            }
        });
    }

    private void setText_kP_adjuster(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_kP_adjuster.setText(str);
            }
        });
    }

    private void setText_kI_adjuster(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_kI_adjuster.setText(str);
            }
        });
    }

    private void setText_kD_adjuster(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_kD_adjuster.setText(str);
            }
        });
    }

    private void setText_multiplierAdjuster(final String str) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView_multiplierAdjusterValue.setText(str);
            }
        });
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event)
    {
        if(event.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR)
        {
            SensorManager.getRotationMatrixFromVector(mRotationMatrix,event.values);
            SensorManager.remapCoordinateSystem(mRotationMatrix,SensorManager.AXIS_X, SensorManager.AXIS_Z, mRotationMatrix);
            SensorManager.getOrientation(mRotationMatrix, orientationVals);
            orientationVals[1]=(float)Math.toDegrees(orientationVals[1]);
        }
    }

    @Override
    public void onResume()
    {
        super.onResume();

        // register this class as a listener for the orientation and
        // accelerometer sensors
        mSensorManager.registerListener(this, mRotVectSensor, 10000);

        if (mAccessory != null)
        {
            setConnectionStatus(true);
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null)
        {
            if (mUsbManager.hasPermission(accessory))
            {
                openAccessory(accessory);
            }
            else
            {
                setConnectionStatus(false);
                synchronized (mUsbReceiver)
                {
                    if (!mPermissionRequestPending)
                    {
                        mUsbManager.requestPermission(accessory, mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        }
        else
        {
            setConnectionStatus(false);
            Log.d(TAG, "mAccessory is null");
        }
    }

    @Override
    protected void onPause() {
        // unregister listener
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onBackPressed()
    {
        if (mAccessory != null)
        {
            new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle("Closing Activity")
                    .setMessage("Are you sure you want to close this application?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
        }
        else
        {
            finish();
        }
    }

    @Override
    public void onDestroy()
    {
        closeAccessory();
        unregisterReceiver(mUsbReceiver);

        if (tts!=null)
        {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }
    @Override
    protected void onStop(){
        super.onStop();

        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("seekBar_Tilt_adjuster", seekBar_Tilt_adjuster.getProgress());
        editor.putInt("seekBar_kP_adjuster", seekBar_kP_adjuster.getProgress());
        editor.putInt("seekBar_kI_adjuster", seekBar_kI_adjuster.getProgress());
        editor.putInt("seekBar_kD_adjuster", seekBar_kI_adjuster.getProgress());
        editor.putInt("seekBar_multiplierAdjuster", seekBar_kI_adjuster.getProgress());

        editor.commit();
    }
    private void openAccessory(UsbAccessory accessory)
    {
        mFileDescriptor = mUsbManager.openAccessory(accessory);

        if (mFileDescriptor != null)
        {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);

            mConnectedThread = new ConnectedThread(this);
            mConnectedThread.start();

            setConnectionStatus(true);

            Log.d(TAG, "Accessory opened");
        }
        else
        {
            setConnectionStatus(false);
            Log.d(TAG, "Accessory open failed");
        }
    }

    private void setConnectionStatus(boolean connected)
    {
        connectionStatus.setText(connected ? "Connected" : "Disconnected");
    }

    private void closeAccessory()
    {
        setConnectionStatus(false);

        // Cancel any thread currently running a connection
        if (mConnectedThread != null)
        {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Close all streams
        try
        {
            if (mInputStream != null)
                mInputStream.close();
        }
        catch (Exception ignored) {}
        finally
        {
            mInputStream = null;
        }

        try {
            if (mOutputStream != null)
                mOutputStream.close();
        }
        catch (Exception ignored) {}
        finally
        {
            mOutputStream = null;
        }

        try
        {
            if (mFileDescriptor != null)
                mFileDescriptor.close();
        }
        catch (IOException ignored) {}
        finally
        {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    public void sendDirection(Direction direction)
    {
        switch (direction)
        {
            case FORWARDS:
                sendCharacter('F');
                break;
            case BACKWARDS:
                sendCharacter('B');
                break;
            case LEFT:
                sendCharacter('L');
                break;
            case RIGHT:
                sendCharacter('R');
                break;
            case STOP:
                sendCharacter('S');
        }
    }

    public void sendSpeed(float value)
    {
        int roundedValue = Math.round(value);

//        Log.e(TAG, "sending " + roundedValue);
        if (mOutputStream != null)
            try {
                /**
                 * We'll make a byte array where the first 2 bytes hold the value for
                 * the left motor speed and the last 2 for the right
                 */
                byte[] leftArray = ByteBuffer.allocate(2).putInt(roundedValue).array();
                byte[] rightArray = ByteBuffer.allocate(2).putInt(roundedValue).array();
                byte[] combinedArray = new byte[4];
                System.arraycopy(leftArray, 0, combinedArray, 0, 2);
                System.arraycopy(rightArray, 0, combinedArray, 2, 2);
                mOutputStream.write(combinedArray);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
    }

    public void sendCharacter(char character)
    {
        byte buffer = (byte)character;
        Log.d(TAG, String.format("writing character %c", character));
        if (mOutputStream != null)
        {
            try
            {
                mOutputStream.write(buffer);
            }
            catch (IOException e)
            {
                Log.e(TAG, "write failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread
    {
        Activity activity;
        TextView mTextView;
        byte[] buffer = new byte[1024];
        boolean running;

        public ConnectedThread(Activity activity)
        {
            this.activity = activity;
            mTextView = (TextView) findViewById(R.id.textView);
            running = true;
        }

        public void run()
        {
            while (running)
            {
                try
                {
                    int bytes = mInputStream.read(buffer);
                    if (bytes > 3)
                    { // The message is 4 bytes long
                        activity.runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                long timer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getLong();
                                mTextView.setText(Long.toString(timer));
                            }
                        });
                    }
                } catch (Exception ignore) {
                }
            }
        }

        public void cancel()
        {
            running = false;
        }
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action))
            {
                synchronized (this)
                {
                    UsbAccessory accessory = UsbManager.getAccessory(intent);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    {
                        openAccessory(accessory);
                    }
                    else
                    {
                        Log.d(TAG, "Permission denied for accessory " + accessory);
                    }

                    mPermissionRequestPending = false;
                }
            }
            else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action))
            {
                UsbAccessory accessory = UsbManager.getAccessory(intent);
                if (accessory != null && accessory.equals(mAccessory))
                    closeAccessory();
            }
        }
    };

    class Balancer
    {
        private int targetAngle;
        private int currentAngle;
        private float previousErrorAngle;

        private float P = 0;
        private float I = 0;
        private float D = 0;
        private float PID = 0;

        private long lastTime = 0;

        private float integratedError = 0;

        private int seekbar_tilt_adjuster_value;
        private float seekbar_kP_adjuster_value;
        private float seekbar_kI_adjuster_value;
        private float seekbar_kD_adjuster_value;
        private float seekbar_multiplier_adjuster_value;

        public void balance()
        {
            seekbar_tilt_adjuster_value = seekBar_Tilt_adjuster.getProgress() - 500;

            // Divide by 100 to get floats from int-only SeekBars.
            seekbar_kP_adjuster_value   = (float)seekBar_kP_adjuster.getProgress() / (float)100;
            seekbar_kI_adjuster_value   = (float)seekBar_kI_adjuster.getProgress() / (float)100;
            seekbar_kD_adjuster_value   = (float)seekBar_kD_adjuster.getProgress() / (float)100;
            seekbar_multiplier_adjuster_value = (float)seekBar_multiplierAdjuster.getProgress() / (float)100;

            setText_tilt_adjuster(Integer.toString(seekbar_tilt_adjuster_value));
            setText_kP_adjuster(Float.toString(seekbar_kP_adjuster_value));
            setText_kI_adjuster(Float.toString(seekbar_kI_adjuster_value));
            setText_kD_adjuster(Float.toString(seekbar_kD_adjuster_value));
            setText_multiplierAdjuster(Float.toString(seekbar_multiplier_adjuster_value));

            // Defaults to 0. Can be tuned to stand up completely straight.
            targetAngle = seekbar_tilt_adjuster_value;
            currentAngle = (Math.round(orientationVals[1]  * 100));

            float errorAngle = ((float)targetAngle - (float)currentAngle) / (float)100;
            setText_current_angle(Float.toString(errorAngle));

            long time = System.currentTimeMillis();
            long dt = time - lastTime;
            P = seekbar_kP_adjuster_value * errorAngle;
            integratedError += errorAngle * dt;
            I = seekbar_kI_adjuster_value * constrain(integratedError, -1, 1);
            D = seekbar_kD_adjuster_value * (errorAngle - previousErrorAngle) / dt;
            previousErrorAngle = errorAngle;
            lastTime = time;

            PID = (P + I + D) * seekbar_multiplier_adjuster_value;

            Log.i("PID", "PID " + ((float)Math.round(PID * 1000) / (float)1000));
            sendSpeed(PID);
        }

        // I can't figure out how to use android.util.MathUtil's constrain, which apparently exists. So making my own.
        public float constrain(float value, float lower, float upper)
        {
            if (value > upper) {
                return upper;
            } else if (value < lower) {
                return lower;
            } else {
                return value;
            }
        }
    }



}
