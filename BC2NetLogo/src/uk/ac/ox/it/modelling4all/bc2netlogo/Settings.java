/**
 * 
 */
package uk.ac.ox.it.modelling4all.bc2netlogo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Set;
import java.util.Map.Entry;
import java.util.prefs.Preferences;

/**
 * @author Ken Kahn
 * 
 * Since java.util.prefs.Preferences doesn't work on all operating systems and configuration (e.g. user permissions)
 * this falls back on a file for reading and writing preferences when java.util.prefs.Preferences is unavailable
 * Perhaps should just abandon it and only rely upon the file system
 *
 */
public class Settings {

    private static Preferences preferences;
    private static PrintStream preferencePrintStream = null;
    private static InputStream preferenceInputStream = null;
    private static HashMap<String, String> currentPreferences= new HashMap<String, String>();

    public static boolean initialise() {
        preferences = Preferences.userNodeForPackage(BC2NetLogo.class).parent();
        if (preferences != null) {
            try {
                // see if it is working -- may run into permission problems
                preferences.put("test", "ok");
                if (preferences.get("test", null) == null) {
                    preferences = null; // can't update preferences so ignore them
                }
            } catch (Exception e) {
                preferences = null;
            }
        }
        if (preferences == null) {
            if (!readPreferenceStream()) {
                // report an error?
                return false;
            }
        }
        return true;
    }

    public static String getPreference(String key, String defaultValue) {
        if (preferences == null) {
            String value = currentPreferences.get(key);
            if (value == null) {
                return defaultValue;
            } else {
                return value;
            }
        } else {
            return preferences.get(key, defaultValue);
        }
    }

    public static boolean getPreferenceBoolean(String key, Boolean defaultValue) {
        if (preferences == null) {
            return Boolean.parseBoolean(getPreference(key, defaultValue.toString()));
        } else {
            return preferences.getBoolean(key, defaultValue);
        }
    }

    public static void putPreference(String key, String value) {
        if (preferences == null) {
            currentPreferences.put(key, value);
        } else {
            preferences.put(key, value);
        }
    }

    public static void putPreferenceBoolean(String key, Boolean value) {
        if (preferences == null) {
            currentPreferences.put(key, value.toString());
        } else {
            preferences.putBoolean(key, value);
        }
    }

    public static void removePreference(String key) {
        if (preferences == null) {
            currentPreferences.remove(key);
        } else {
            preferences.remove(key);
        }
    }

    public static PrintStream getPreferenceWriteStream() {
        if (preferencePrintStream == null) {
            try {
                preferencePrintStream = new PrintStream("preferences.settings.txt");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        return preferencePrintStream;
    }

    public static InputStream getPreferenceReadStream() {
        if (preferenceInputStream == null) {
            try {
                preferenceInputStream = new FileInputStream("preferences.settings.txt");
            } catch (FileNotFoundException e) {
                if (writePreferenceStream("userGuid=new\nsessionGuid=new\n")) {
                    return getPreferenceReadStream();
                } else { 
                    return null;
                }
            }
        }
        return preferenceInputStream;
    }

    public static void closePreferenceReadStream() {
        if (preferenceInputStream != null) {
            try {
                preferenceInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            preferenceInputStream = null;
        }
    }

    public static boolean writePreferenceStream(String data) {
        PrintStream stream = getPreferenceWriteStream();
        if (stream == null) {
            return false;
        }
        try {
            stream.print(data);
            stream.close();
            preferencePrintStream = null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean readPreferenceStream() {
        InputStream stream = getPreferenceReadStream();
        String preferences= convertStreamToString(stream);
        closePreferenceReadStream();
        if (preferences.isEmpty()) {
            return false;
        }
        int index = 0;
        int equalIndex;
        while ((equalIndex = preferences.indexOf("=", index)) >= 0) {
            int lineEnd = preferences.indexOf("\n", equalIndex);
            if (lineEnd < 0) {
                // last line
                lineEnd = preferences.length();
            }
            String key = preferences.substring(index, equalIndex);
            String value = preferences.substring(equalIndex+1, lineEnd);
            currentPreferences.put(key, value);
            index = lineEnd+1;
        }
        return true;
    }

    static String convertStreamToString(InputStream is) {
        // from http://stackoverflow.com/questions/309424/read-convert-an-inputstream-to-a-string
        // modify to close s to avoid annoying compiler warning
        Scanner s = null;
        try {
            s = new Scanner(is);
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        } finally {
            s.close();
        }
    }

    public static void writePreferences() {
        if (preferences != null) {
            // using Java preferences
            return;
        }
        String preferencesString = "";
        Set<Entry<String, String>> entrySet = currentPreferences.entrySet();
        for (Entry<String, String> entry : entrySet) {
            preferencesString += entry.getKey() + "=" + entry.getValue() + "\n";
        }
        writePreferenceStream(preferencesString);
    }

}
