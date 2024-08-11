package nl.negentwee.io;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.util.Log;

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

    public static final double EARTH_RADIUS = 6371000; // Earth radius in meters

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

    public void append(Location loc, boolean hasGPS) {
        if (loc == null) {
            Log.e(TAG, "append: Location to log is null");
            return;
        }
        if (lastLocation != null) {
            if (lastLocation.getLatitude() == loc.getLatitude() && lastLocation.getLongitude() == loc.getLongitude() &&
                    loc.getAccuracy() == lastLocation.getAccuracy()) {
                Log.d(TAG, "append: Skipping this particular location; is duplicate.");
                return;
            }
        }
        if(lastLocation != null && calculateDistance(loc, lastLocation) < 20) {
            Log.d(TAG, "append: Distance is too little between current and last coordinate, skipping.");
            return;
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

    /**
     * Calculates the distance between two coordinate sets.
     * @param loc1 location one used in the comparison.
     * @param loc2 location two used in the comparison.
     * @return the amount of physical meters.
     */
    public static double calculateDistance(Location loc1, Location loc2) {
        double lat1 = loc1.getLatitude(), lon1 = loc1.getLongitude();
        double lat2 = loc2.getLatitude(), lon2 = loc2.getLongitude();
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = EARTH_RADIUS * c;

        return distance;
    }

}
