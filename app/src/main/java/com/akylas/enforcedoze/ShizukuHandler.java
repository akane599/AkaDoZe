package com.akylas.enforcedoze;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuHandler {
    private static final String TAG = "ShizukuHandler";
    private static ShizukuHandler instance;
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newSingleThreadExecutor();
    private final AccessManager access;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<OnAvailibilityChange> availabilityListeners = new CopyOnWriteArraySet<>();
    private volatile boolean isShizukuAvailable = false;

    public interface OnAvailibilityChange {
        void onChange(Boolean value);
    }

    private ShizukuHandler(Context context) {
        access = AccessManager.getInstance(context);
        isShizukuAvailable = access.getShizukuState().getLevel() != AccessLevel.NONE;
        access.addShizukuListener(state -> {
            isShizukuAvailable = state.getLevel() != AccessLevel.NONE;
            for (OnAvailibilityChange listener : availabilityListeners) {
                listener.onChange(isShizukuAvailable);
            }
        });
    }

    public static synchronized ShizukuHandler getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuHandler(context);
        }
        return instance;
    }

    // Compatibility name: registration is now additive, so activities cannot evict the service.
    public void setOnAvailibilityChangeListener(OnAvailibilityChange listener) {
        if (listener != null && availabilityListeners.add(listener)) {
            main.post(() -> {
                if (availabilityListeners.contains(listener)) listener.onChange(isShizukuAvailable);
            });
        }
    }

    public void removeOnAvailibilityChangeListener(OnAvailibilityChange listener) {
        availabilityListeners.remove(listener);
    }

    public void checkShizukuAvailability() {
        access.refreshShizuku();
        isShizukuAvailable = access.getShizukuState().getLevel() != AccessLevel.NONE;
    }

    public boolean isShizukuAvailable() {
        return isShizukuAvailable;
    }

    public int checkShizukuPermission() {
        return access.checkShizukuPermission();
    }

    public void requestShizukuPermission() {
        access.requestShizukuPermission();
    }

    public void removePermissionResultListener() {
        // AccessManager owns the app-lifetime listener; one activity must not remove it for others.
    }

    /**
     * Execute a shell command using Shizuku
     * @param command The command to execute
     * @param callback Callback to receive the output
     */
    public void executeCommand(@NonNull String command, @NonNull OnCommandResultListener callback) {
        executeCommand(command, callback, false);
    }

    Method shizukuNewProcessMethod = null;
    /**
     * Execute a shell command using Shizuku
     * @param command The command to execute
     * @param callback Callback to receive the output
     * @param printOutput Whether to print the output to logs
     */
    public void executeCommand(@NonNull String command, @NonNull OnCommandResultListener callback, boolean printOutput) {
        COMMAND_EXECUTOR.execute(() -> {
            List<String> stdout = new ArrayList<>();
            List<String> stderr = new ArrayList<>();
            int exitCode = -1;

            try {
                if (!isShizukuAvailable) {
                    Log.e(TAG, "Shizuku is not available");
                    callback.onCommandResult(0, -1, stdout, stderr);
                    return;
                }

                if (checkShizukuPermission() != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Shizuku permission not granted");
                    callback.onCommandResult(0, -1, stdout, stderr);
                    return;
                }
                if (shizukuNewProcessMethod == null) {
                    Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                    shizukuNewProcessMethod = clazz.getDeclaredMethod("newProcess", String[].class,String[].class, String.class);
                    shizukuNewProcessMethod.setAccessible(true);
                }
                String[] cmd = new String[] { "sh", "-c", command };
                Object[] invokeArgs = new Object[] { cmd, null, null };

                ShizukuRemoteProcess process = (ShizukuRemoteProcess) shizukuNewProcessMethod.invoke(null, invokeArgs);
//                ShizukuRemoteProcess process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);

                try {
                    // Drain stderr alongside stdout so either pipe can fill safely.
                    FutureTask<Void> stderrTask = new FutureTask<>(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stderr.add(line);
                                if (printOutput) {
                                    Log.e(TAG, line);
                                }
                            }
                        }
                        return null;
                    });
                    new Thread(stderrTask, "Shizuku-stderr").start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stdout.add(line);
                            if (printOutput) {
                                Log.i(TAG, line);
                            }
                        }
                    }
                    stderrTask.get();
                    exitCode = process.waitFor();
                } finally {
                    process.destroy();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error executing command: " + e.getMessage());
                e.printStackTrace();
            }

            callback.onCommandResult(0, exitCode, stdout, stderr);
        });
    }

    /**
     * Callback interface for command execution results
     */
    public interface OnCommandResultListener {
        void onCommandResult(int commandCode, int exitCode, List<String> stdout, List<String> stderr);
    }
}
