package org.treinchauffeur.testtim.io;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocationLogger {
    Context c;
    File file;
    FileWriter fileWriter;
    BufferedWriter writer;
    Location lastLocation;

    public static final String logFileSuffix = "_TestTim.txt";
    public static final String jsonFileSuffix = "_TestTim_json.json";

    @SuppressLint("ConstantLocale")
    public static final SimpleDateFormat dateFormatter = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
    SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault());


    String TAG = "LocationLogger";

    public LocationLogger(Context c) {
        this.c = c;
    }

    public void init() {
        try {
            String dateString = dateFormatter.format(new Date());
            file = new File(c.getFilesDir().getPath() + "/" + dateString + logFileSuffix);

            if (!file.exists()) {
                Log.d(TAG, "init: creating file " + file.getPath());
                boolean newFile = file.createNewFile();
                if (!newFile) Log.e(TAG, "init: Couldn't create file!");
            }
            fileWriter = new FileWriter(file, true);
            writer = new BufferedWriter(fileWriter);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void append(@NonNull Location loc, boolean hasGPS) {
        if (lastLocation != null) {
            if (lastLocation.getLatitude() == loc.getLatitude() && lastLocation.getLongitude() == loc.getLongitude() &&
                    loc.getAccuracy() == lastLocation.getAccuracy()) {
                Log.d(TAG, "append: Skipping this particular location; is duplicate.");
                return;
            }
        }
        try {
            init();

            String dateString = dateTimeFormatter.format(new Date());
            writer.write(dateString + " " + loc.getLatitude() + " " + loc.getLongitude() + " +-" + (int) loc.getAccuracy() +
                    " " + (loc.hasSpeed() ? ((int) (loc.getSpeed() * 3.6)) : "-1") + " " + (hasGPS ? "GNSS-Fix" : "No-Fix"));
            writer.newLine();

            close();

            lastLocation = loc;
        } catch (IOException e) {
            Log.e(TAG, "Append: ", e);
        }
    }

    public void customMessage(String string) {
        try {
            init();

            String dateString = dateTimeFormatter.format(new Date());
            writer.write(dateString + " <!-- " + string + " -->");
            writer.newLine();

            close();
        } catch (IOException e) {
            Log.e(TAG, "Append: ", e);
        }
    }

    public void close() {
        try {
            writer.close();
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
