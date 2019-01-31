package com.curtismj.logoplus;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import com.curtismj.logoplus.persist.LogoDao;
import com.curtismj.logoplus.persist.LogoDatabase;
import com.curtismj.logoplus.persist.UIState;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import eu.chainfire.libsuperuser.Shell;

public class LogoPlusService extends Service {
    public static final int SERVICE_START = 0;
    public static final int NOTIF_PUSH = 1;
    public static final int ENTER_IDLE = 2;
    public static final int EXIT_IDLE = 3;
    public static final int APPLY_EFFECT_MSG = 4;
    public static final int START_BOUNCE = 5;
    public static  final  String START_BROADCAST = BuildConfig.APPLICATION_ID + ".ServiceAlive";
    public static  final  String START_FAIL_BROADCAST = BuildConfig.APPLICATION_ID + ".ServiceFailedStart";
    public static  final  String APPLY_EFFECT = BuildConfig.APPLICATION_ID + ".ApplyEffect";

    public static final int EFFECT_NONE = 0;
    public static final int EFFECT_STATIC= 1;
    public static final int EFFECT_PULSE = 2;
    public static final int EFFECT_RAINBOW = 3;
    public static final int EFFECT_PINWHEEL = 4;

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private Messenger mMessenger;
    private  boolean RootAvail = false;
    private Shell.Interactive rootSession = null;
    private String fadeoutBin;
    private PowerManager pm;
    private int[] latestNotifs = new int[0];
    private  boolean inIdle = false;
    private  boolean inEffectOn = false;
    private  BroadcastReceiver offReceiver;
    private PowerManager.WakeLock handlerLock;
    private LogoDatabase db;
    private LogoDao dao;
    private UIState state;

    private void fetchState()
    {
        Log.d("debug", "db invalidated, refetching state");
        state = dao.getUIState();
    }

    private void failOut()
    {
        try
        {
            Log.d("debug", "failed start service");
            Intent broadCastIntent = new Intent();
            broadCastIntent.setAction(START_FAIL_BROADCAST);
            sendBroadcast(broadCastIntent);
            stopSelf();
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
    }

    private void notifyStarted()
    {
        Intent broadCastIntent = new Intent();
        broadCastIntent.setAction(START_BROADCAST);
        sendBroadcast(broadCastIntent);
    }

    private void blankLights()
    {
        rootSession.waitForIdle();
        rootSession.addCommand(new String[]{
                fadeoutBin
        });
        rootSession.waitForIdle();
    }

    public void  runEffect()
    {
        Log.d("debug", "effect start");
        if (!inEffectOn) {
            inEffectOn = true;
            switch (state.passiveEffect)
            {
                case EFFECT_NONE:
                    blankLights();
                    break;
                case EFFECT_STATIC:
                    runProgram(MicroCodeManager.staticProgramBuild(state.passiveColor));
                    break;
                case EFFECT_PULSE:
                    runProgram(MicroCodeManager.pulseProgramBuild(state.effectLength,state.passiveColor));
                    break;
                case EFFECT_RAINBOW:
                    runProgram(MicroCodeManager.rainbowProgramBuild(state.effectLength, false));
                    break;
                case EFFECT_PINWHEEL:
                    runProgram(MicroCodeManager.rainbowProgramBuild(state.effectLength, true));
                    break;
            }

        }
    }

    private void enterIdle()
    {
        Log.d("debug", "enter idle state requested");
        if (!inIdle) {
            inIdle = true;
            Log.d("debug", "entering idle state");
            blankLights();
        }
        if (pm.isInteractive())
        {
            Log.d("debug", "interactive idle, effect must run");
            runEffect();
        }
        else if (state.powerSave)
        {
            inEffectOn = false;
            blankLights();
        }
    }

    private void runProgram(String[] program)
    {
        rootSession.waitForIdle();
        rootSession.addCommand(new String[]{
                fadeoutBin,
                "echo \"" + program[3] + "\" > /sys/class/leds/lp5523:channel0/device/memory",
                "echo \"" + program[0] + "\" > /sys/class/leds/lp5523:channel0/device/prog_1_start",
                "echo \"" + program[1] + "\" > /sys/class/leds/lp5523:channel0/device/prog_2_start",
                "echo \"" + program[2] + "\" > /sys/class/leds/lp5523:channel0/device/prog_3_start",
                "echo \"1\" > /sys/class/leds/lp5523:channel0/device/run_engine",
                "echo \"" + state.brightness + "\" > /sys/class/leds/lp5523:channel0/device/master_fader1"
        });
        rootSession.waitForIdle();
    }

    // Handler that receives messages from the thread
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            // NB: Only acquire wakelock in the process of applying effects, bureaucracy can wait
            if (msg.what != SERVICE_START && !RootAvail) return;
            switch (msg.what) {
                case SERVICE_START:
                    Log.d(BuildConfig.APPLICATION_ID, "Service Starting");
                    db = LogoDatabase.getInstance(getApplicationContext());
                    dao = db.logoDao();
                    fetchState();
                    if (state == null || !state.serviceEnabled)
                    {
                        stopSelf();
                        return;
                    }

                    rootSession = new Shell.Builder().
                            setAutoHandler(false).
                            useSU().
                            setWantSTDERR(true).
                            setWatchdogTimeout(5).
                            setMinimalLogging(true).
                            open(new Shell.OnCommandResultListener() {

                                // Callback to report whether the shell was successfully started up
                                @Override
                                public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                    Log.d("debug", "Shell result: " + commandCode + "," + exitCode);
                                    RootAvail = (exitCode == Shell.OnCommandResultListener.SHELL_RUNNING);
                                }
                            });
                    Log.d("debug", "wait for root shell");
                    rootSession.waitForIdle();
                    if (RootAvail) {
                        handlerLock.acquire(10000);
                        Log.d("debug", "got root");
                        rootSession.addCommand(new String[]{
                                "chmod +x " + fadeoutBin,
                                "echo 111111111 > /sys/class/leds/lp5523:channel0/device/master_fader_leds",
                                "echo 0 > /sys/class/leds/lp5523:channel0/device/master_fader1",
                                fadeoutBin
                        });
                        rootSession.waitForIdle();
                        enterIdle();
                        handlerLock.release();
                        notifyStarted();
                    } else {
                        Log.d("debug", "root was denied");
                        failOut();
                    }
                    break;

                case NOTIF_PUSH:
                case EXIT_IDLE:
                    handlerLock.acquire(10000);
                    Log.d("debug", "notif update");
                    Bundle bundle = msg.getData();
                    latestNotifs = (msg.what == NOTIF_PUSH) ? bundle.getIntArray("colors") : latestNotifs;
                    Log.d("debug", "notifs: " + latestNotifs.length);
                    if (!pm.isInteractive()) {
                        Log.d("debug", "not interactive. proceed");
                        if (latestNotifs.length > 0) {
                            Log.d("debug", "notifs loading");
                            inIdle = false;
                            inEffectOn = false;
                            runProgram(MicroCodeManager.notifyProgramBuild(latestNotifs));
                        } else {
                            Log.d("debug", "request idle, no notifs");
                            enterIdle();
                        }
                    }
                    handlerLock.release();
                    break;

                case ENTER_IDLE:
                    handlerLock.acquire(10000);
                    Log.d("debug", "idle requested by outside source");
                    enterIdle();
                    handlerLock.release();
                    break;

                case APPLY_EFFECT_MSG:
                    handlerLock.acquire(10000);
                    Log.d("debug", "updating effect");
                    if (inEffectOn) {
                        inEffectOn = false;
                        Log.d("debug", "re-run effect");
                        fetchState();
                        enterIdle();
                    }
                    handlerLock.release();
                    break;

                case START_BOUNCE:
                    notifyStarted();
                    break;

            }

        }
    }

    public void dumpFadeout(String path) throws IOException {

        OutputStream myOutput = new FileOutputStream(path);
        byte[] buffer = new byte[1024];
        int length;
        InputStream myInput = getAssets().open("fadeout");
        while ((length = myInput.read(buffer)) > 0) {
            myOutput.write(buffer, 0, length);
        }
        myInput.close();
        myOutput.flush();
        myOutput.close();
    }

    private static class LogoBroadcastReceiver extends   BroadcastReceiver {
        private  Handler serviceHandler;

        public LogoBroadcastReceiver(Handler handler)
        {
            serviceHandler = handler;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Message msg;
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    Log.d("debug", "screen off, request service resume");
                     msg = serviceHandler.obtainMessage(EXIT_IDLE);
                    serviceHandler.sendMessage(msg);
                    break;
                case Intent.ACTION_USER_PRESENT:
                    Log.d("debug", "user present, request service to idle");
                    msg = serviceHandler.obtainMessage(ENTER_IDLE);
                    serviceHandler.sendMessage(msg);
                    break;
                case APPLY_EFFECT:
                    Log.d("debug", "effect update requested");
                    msg = serviceHandler.obtainMessage(APPLY_EFFECT_MSG);
                    serviceHandler.sendMessage(msg);
                    break;
                case LogoPlusNotificationListener.START_BROADCAST:
                    Log.d("debug", "listener alive, echo");
                    msg = serviceHandler.obtainMessage(START_BOUNCE);
                    serviceHandler.sendMessage(msg);
                    break;
            }
        }
    }

    @Override
    public void onCreate() {

        fadeoutBin = getFilesDir() + "/fadeout";
        try {
            dumpFadeout(fadeoutBin);
        } catch (IOException e) {
            failOut();
            return;
        }

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        handlerLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":ServiceWorkerLock");

        HandlerThread thread = new HandlerThread("ServiceStartArguments",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
        mMessenger = new Messenger(mServiceHandler);

        Message msg = mServiceHandler.obtainMessage(SERVICE_START);
        mServiceHandler.sendMessage(msg);

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(APPLY_EFFECT);
        intentFilter.addAction(LogoPlusNotificationListener.START_BROADCAST);
        offReceiver = new LogoBroadcastReceiver(mServiceHandler);
        registerReceiver(offReceiver, intentFilter);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If we get killed, after returning from here, restart
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger != null ? mMessenger.getBinder() : null;
    }

    @Override
    public void onDestroy() {
        if (mServiceHandler != null) mServiceLooper.quitSafely();
        if (rootSession != null) {
            rootSession.close();
        }
        if (offReceiver != null) unregisterReceiver(offReceiver);
        rootSession = null;
        dao = null;
        db = null;
    }
}
