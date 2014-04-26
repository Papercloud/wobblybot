package au.com.papercloud.arduino;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

public class MainActivity extends FragmentActivity implements ContinuousDictationFragment.ContinuousDictationFragmentResultsCallback, TextToSpeech.OnInitListener
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

    @Override
    public void onResume()
    {
        super.onResume();

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

}
