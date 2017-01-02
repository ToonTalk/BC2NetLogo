/**
 * 
 */
package uk.ac.ox.it.modelling4all.bc2netlogo;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Date;
//import java.util.prefs.Preferences;

import org.apache.http.client.ClientProtocolException;
import org.nlogo.core.CompilerException;
import org.nlogo.core.Model;
import org.nlogo.api.ConfigurableModelLoader;
import org.nlogo.app.App;
import org.nlogo.app.ModelSaver;
import org.nlogo.fileformat.package$;
import edu.gvsu.cis.masl.channelAPI.ChannelAPI;
import edu.gvsu.cis.masl.channelAPI.ChannelAPI.ChannelException;


/**
 * Top-level class implementing a NetLogo application that gets source updates from the modelling4all.org
 * 
 * @author Ken Kahn
 *
 */
public class BC2NetLogo {

    // https prevents an ISP from injecting JavaScript
    // but https://modelling4all.org doesn't work
    private static String domain = "https://m4a-gae.appspot.com";
    //  when debugging:  "http://localhost:8888"
    private static String behaviourComposerBaseURL;

    //    private static final String BC_AUXILIARY_FILE = "bc_auxiliary_file_from_download_tab_version_20.nls";
    //    private static final String CURRENT_VERSION = "1";
    //    private static String auxiliaryFile = null;
    private static String currentCode;
    private static String currentRestOfModel;
    private static String currentExperiments;
    private static boolean sessionChanged;
    private static String userGuid;
    private static String sessionGuid;
    private static boolean translate;
    private static boolean internetAccess;
    private static boolean experimentsEdited;
    //    private static TimerTask openChannelTimerTask;
    //    private static Timer openChannelTimer;
    private static String channelToken;
    private static ChannelAPI channel;
    private static Exception mostRecentException;
    private static String urlForNewURL;
    private static boolean debugging;
    private static int reconnectionCount = 0;
    private static boolean waitingForNewConnection = false;
    private static PrintStream errorLog = null;

    public static void main(String[] argv) {
        // following was causing old preferences to be used inappropriately
        //	Preferences oldPreferences = Preferences.userNodeForPackage(BC2NetLogo.class);
        // shared preferences with BC2NetLogoInstaller
        //	preferences = Preferences.userNodeForPackage(BC2NetLogo.class).parent();
        Settings.initialise();
        String directory = Settings.getPreference("BC2NetLogoDirectory", "");
        if (!directory.isEmpty()) {
            // was tidier to keep all the BC2NetLogo files in its own folder (BC2NetLogo)
            // but then NetLogo couldn't find help and documentation files (and the models library)
            System.setProperty("user.dir", directory.replaceAll("\\\\", "/")); // + "/BC2NetLogo");
            //	    String command = "set-current-directory \"" + directory.replaceAll("\\\\", "/") + "/BC2NetLogo\"";
            //	    try {
            //		App.app().command(command);
            //	    } catch (CompilerException e) {
            //		e.printStackTrace();
            //	    }
        }
        String appArgs[] = new String[0];
        App.main(appArgs);
        printUserMessage("A web page will open soon in your browser. Please wait.");
        //	printUserMessage("To import a different model click the 'Code' tab.");
        //	if (currentCode == null) {
        //	    addCommentsToEmptyModel();
        //	}
        String newDomain = commandLineArg("domain", argv);
        if (newDomain == null) {
            newDomain = Settings.getPreference("server", domain);
        }
        if (newDomain != null) {
            if (!newDomain.startsWith("http")) {
                newDomain = "https://" + newDomain;
            }
            if (newDomain.endsWith("/")) {
                domain = newDomain.substring(0, newDomain.length()-1);
            } else {
                domain = newDomain;
            }
            if (domain.equals("https://modelling4all.org") || domain.equals("http://modelling4all.org")) {
                // otherwise posts to server fail
                domain = "https://m4a-gae.appspot.com";
            }
            Settings.putPreference("server", domain);
        }
        String epidemicGameMakerString = commandLineArg("EpidemicGameMaker", argv);
        boolean epidemicGameMaker = epidemicGameMakerString != null && epidemicGameMakerString.equals("1");
        behaviourComposerBaseURL = domain;
        if (!domain.startsWith("http://localhost") && !domain.startsWith("http://127.0.0.1")) {
            // local host is the Behaviour Composer -- deployed we need the /m
            behaviourComposerBaseURL += "/m";
        }
        String internetArg = commandLineArg("internet", argv);
        internetAccess = internetArg == null || !internetArg.equals("0");
        String debugArg = commandLineArg("debug", argv);
        debugging = debugArg != null && debugArg.equals("1");
        String newUserGuid = commandLineArg("user", argv);
        if (newUserGuid != null) {
            if (newUserGuid.equals("new")) {
                Settings.removePreference("userGuid");
            } else {
                Settings.putPreference("userGuid", newUserGuid);
            }
            // new user needs a new session
            Settings.removePreference("sessionGuid");
        }
        urlForNewURL = domain + "/p/bc2netlogo.txt";
        userGuid = Settings.getPreference("userGuid", null);
        //	if (epidemicGameMaker) {
        //	    // simplest to start over again with running EGM
        //	    // problem this avoids is if a user runs both with and without EGM and the session GUID is inappropriate
        //	    Settings.removePreference("sessionGuid");
        //	    sessionGuid = "new";
        //	} else {
        sessionGuid = Settings.getPreference("sessionGuid", "new");
        //	}
        // better to rely upon cookies than the following
        //	if (userGuid == null) {
        //	    userGuid = "new";
        //	}
        //	if (sessionGuid == null) {
        //	    sessionGuid = "new";
        //	    displayUserMessage("Java preferences not set. Each time you start BC2NetLogo it will treat you as a new user. If you save the Behaviour Composer URL and paste it into the advanced settings 'user' and 'session' fields then you'll be able to resume your efforts.");
        //	}
        if (userGuid != null) {
            String modelGuid = Settings.getPreference("modelGuid", null);
            String parameters = Settings.getPreference("parameters", null);
            if (epidemicGameMaker || (parameters != null && parameters.contains("EGM=1"))) {
                urlForNewURL += "?user=" + userGuid + "&session=new&EGM=1";
            } else if (modelGuid != null) {
                urlForNewURL += "?user=" + userGuid + "&frozen=" + modelGuid;
                // just use once
                Settings.removePreference("modelGuid");
            } else {
                urlForNewURL += "?user=" + userGuid + "&session=" + sessionGuid;
            }
            urlForNewURL += "&domain=" + domain;
        } else {
            urlForNewURL += "?domain=" + domain + "&cookiesEnabled=1";
        }
        translate = Settings.getPreferenceBoolean("translate", false);
        printUserMessage("about to write preferences");
        Settings.writePreferences();
        printUserMessage("about to getConnectionToServer");
        getConnectionToServer(false);
    }

    public static void getConnectionToServer(boolean reconnecting) {
        String urlFromServer;
        if (reconnecting) {
            // consider responding differently if count is too high
            // TODO: reconnectingFromNetLogo parameter should probably be deprecated or removed
            urlForNewURL = domain + "/p/bc2netlogo.txt?reconnectingFromNetLogo=" + reconnectionCount 
                    + "&user=" + userGuid
                    + "&session=" + sessionGuid
                    + "&domain=" + domain;
            reconnectionCount++;
            urlFromServer = urlContents(urlForNewURL);
        } else {
            urlFromServer = urlContents(urlForNewURL);
        }
        //	System.out.println("BC2NetLogo's connection to server is " + urlFromServer + " obtained using " + urlForNewURL); // debug this
        if (urlFromServer != null && urlFromServer.startsWith("http")) {
            urlFromServer = urlFromServer.trim();
        } else {
            String message = "Failed to connect to the Modelling4All server. Perhaps there is a network problem. Unable to read this URL: " + urlForNewURL;
            if (mostRecentException != null) {
                message += " Reported error: " + mostRecentException.getMessage();
                mostRecentException = null;
            }
            if (userYesOrNo(message + " Do you want to try to connect again?")) {
                getConnectionToServer(reconnecting);
                return;
            } else {
                displayUserMessage("No connection to the Behaviour Composer. You may run or save your model but updates will be lost.");
                return;
                //		System.exit(0);
            }
        }
        //	String latestVersion = getURLParameter("bc2NetLogoVersion", urlFromServer);
        //	if (!CURRENT_VERSION.equals(latestVersion) && latestVersion != null) {
        //	    displayUserMessage("The Behaviour Composer has been updated since this program was downloaded. The page with download instructions will now be opened.");
        //	    try {
        //		Desktop.getDesktop().browse(new URI("http://resources.modelling4all.org/Home/behaviour-composer-direct-to-netlogo-guide"));
        //	    } catch (Exception e1) {
        //		e1.printStackTrace();
        //	    }
        //	    System.exit(0);
        //	}
        if (userGuid == null || userGuid.equals("new")) {
            userGuid = getUserGuid(urlFromServer);
            Settings.putPreference("userGuid", userGuid);
            Settings.writePreferences();
        }
        sessionGuid = getSessionGuid(urlFromServer);
        Settings.putPreference("sessionGuid", sessionGuid);
        String parameters = Settings.getPreference("parameters", "");
        if (parameters.length() > 0) {
            if (parameters.charAt(0) != '&') {
                parameters = "&" + parameters;
            }
        }
        urlFromServer += parameters;
        //	currentExperiments = Settings.getPreference(EXPERIMENTS_OF_SESSION + sessionGuid, null);
        try {
            printUserMessage("about to openChannel");
            openChannel(getChannelTokenToNetLogo(urlFromServer));
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
        }	
        try {
            if (!urlFromServer.contains(behaviourComposerBaseURL)) {
                urlFromServer = urlFromServer.replace("http://m.modelling4all.org", domain);
            }
            if (!internetAccess) {
                urlFromServer += "&internetAccess=0";
            }
            if (translate) {
                urlFromServer = urlFromServer.replace("index.html", "translate.html");
            } else if (debugging && (urlFromServer.startsWith("http://localhost") || urlFromServer.startsWith("http://127.0.0.1"))) {
                urlFromServer = urlFromServer.replace("index.html", "index-dev.html");
                urlFromServer += "&gwt.codesvr=127.0.0.1:9997";
            }
            if (reconnecting) {
                printUserMessage("Connection to server re-established.");
            } else {
                try {
                    printUserMessage("about to DesktopApi.browse");
                    DesktopApi.browse(new URI(urlFromServer));
                } catch (Exception e) {
                    printUserMessage("Failed to open the following URL. Please copy and paste this into a browser: " + urlFromServer);
                }
            }
        } catch (Exception e1) {
            e1.printStackTrace(getErrorLog());
        }
        waitingForNewConnection = false;
        Settings.writePreferences();
        //	InputStream auxiliaryFileStream = 
        //		BC2NetLogo.class.getResourceAsStream("resources/" + BC_AUXILIARY_FILE);
        //	try {
        //	    auxiliaryFile  = 
        //		    bufferedReaderToString(new BufferedReader(new InputStreamReader(auxiliaryFileStream)));
        //	} catch (IOException e) {
        //	    e.printStackTrace();
        //	}
    }

    //    public static void reopenChannel() throws ClientProtocolException, IOException, ChannelException {
    //	reopenChannel(channelToken);
    //    }

    public static void reopenChannel(String newChannelToken) throws ClientProtocolException, IOException, ChannelException {
        // following makes sense but caused errors
        //	if (channel != null) {
        //	    channel.close();
        //	}
        //	if (openChannelTimer != null) {
        //	    openChannelTimer.cancel();
        //	}
        //	openChannelTimer = null; // restart with fresh timer
        //	openChannelTimerTask = null; // and task
        openChannel(newChannelToken);
    }

    protected static void openChannel(String currentChannelToken)
            throws IOException, ClientProtocolException, ChannelException {
        //	System.out.println("BC2NetLogo is opening channel " + currentChannelToken); // debug this
        //	boolean newToken = channelToken != currentChannelToken;
        channelToken = currentChannelToken;
        //	if (openChannelTimerTask == null) {
        //	    openChannelTimerTask = new TimerTask() {
        //
        //		@Override
        //		public void run() {
        try {
            printUserMessage("new ChannelListener");
            ChannelListener channelService = new ChannelListener();
            printUserMessage("new ChannelAPI");
            channel = new ChannelAPI(behaviourComposerBaseURL, channelToken, sessionGuid, channelService);
            printUserMessage("channel.open");
            channel.open(internetAccess);
            printUserMessage("channel opened");
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
        }
        //		}
        //
        //	    };
        //	}
        //	if (openChannelTimer == null) {
        //	    openChannelTimer = new Timer();
        //	    long frequency = 1000 * 60 * 60; // one hour
        //	    openChannelTimer.scheduleAtFixedRate(openChannelTimerTask, 0, frequency);
        //	} else if (newToken) {
        //	    openChannelTimerTask.run();
        //	}
    }

    private static String commandLineArg(String argName, String[] argv) {
        String searchArg = "-" + argName;
        for (int i = 0; i < argv.length; i += 2) {
            if (argv[i].equals(searchArg)) {
                return argv[i+1];
            }
        }
        return null;
    }

    private static String getSessionGuid(String url) {
        String parameter = getURLParameter("share", url);
        if (parameter == null) {
            parameter = getURLParameter("session", url);
        }
        return parameter;
    }

    private static String getFrozenGuid(String url) {
        return getURLParameter("frozen", url);
    }

    private static String getUserGuid(String url) {
        return getURLParameter("user", url);
    }

    private static String getChannelTokenToNetLogo(String url) {
        return getURLParameter("bc2NetLogoChannelToken", url);
    }

    public static String getURLParameter(String name, String url) {
        int parameterIndex = url.indexOf(name + "=");
        if (parameterIndex < 0) {
            return null;
        } else {
            int equalIndex = url.indexOf("=", parameterIndex);
            int endIndex = url.indexOf("&", equalIndex);
            if (endIndex < 0) {
                // is last one
                endIndex = url.length();
            }
            return url.substring(equalIndex+1, endIndex);
        }
    }

    public static void displayUserMessage(String message) {
        try {
            App.app().command("user-message \"" + message + "\"");
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
        }
    }

    public static void printUserMessage(String message) {
        try {
            // embedded quotes confuse NetLogo so replace with single quotes
            App.app().commandLater("print \"" + message.replace('"',  '\'') + "\"");
        } catch (CompilerException e) {
            e.printStackTrace(getErrorLog());
        }
    }

    public static Boolean userYesOrNo(String message) {
        try {
            Object result = App.app().report("user-yes-or-no? \"" + message.replace("\n", "\\n") + "\"");
            if (result instanceof org.nlogo.nvm.HaltException) {
                ((org.nlogo.nvm.HaltException) result).printStackTrace(getErrorLog());
                return false;
            }
            return (Boolean) result;
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
            return false;
        }
    }

    public static String urlContents(String urlString) {
        try {
            URL url = new URL(urlString);
            URLConnection connection = url.openConnection();
            // following needed?
            //	    connection.setRequestProperty("User-Agent", ???);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            return bufferedReaderToString(new BufferedReader(new InputStreamReader(connection.getInputStream())));
        } catch (Exception e) {
            mostRecentException = e;
            PrintStream errorLog2 = getErrorLog();
            if (errorLog2 != null) {
                // creating the log may have failed -- e.g. permissions
                e.printStackTrace(errorLog2);
            }
            return null;
        }
    }

    private static String bufferedReaderToString(BufferedReader in) throws IOException {
        StringBuilder contents = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            contents.append(line + "\r");
        }
        in.close();
        return contents.toString();
    }

    public static void processMessage(String message) {
        String[] split = message.split(";", 2);
        // model code URL and sessionGuid
        if (split.length < 2) {
            displayUserMessage("Unable to process the message received from the server: " + message);
            return;
        }
        //	Preferences preferences = Preferences.userNodeForPackage(BC2NetLogo.class).parent();
        String oldSessionGuid = Settings.getPreference("sessionGuid", null);
        String newSessionGuid = split[1];
        if (oldSessionGuid != null && !oldSessionGuid.equals(newSessionGuid)) {
            currentCode = null;
            currentRestOfModel = null;
            //	    currentExperiments = Settings.getPreference(EXPERIMENTS_OF_SESSION + newSessionGuid, null);
            sessionChanged = true;
            sessionGuid = newSessionGuid;
        }
        Settings.putPreference("sessionGuid", newSessionGuid);
        Settings.writePreferences();
        openModel(split[0]);
    }

    public static void openModel(final String modelURL) {
        String modelString = urlContents(modelURL);
        if (modelString == null || modelString.isEmpty() || modelString.startsWith("<html")) {
            if (behaviourComposerBaseURL.startsWith("http://localhost") && !modelURL.contains(behaviourComposerBaseURL)) {
                String localURL = modelURL.replace("http://m.modelling4all.org", behaviourComposerBaseURL);
                modelString = urlContents(localURL);
            }
            if (modelString == null || modelString.isEmpty() || modelString.startsWith("<html")) {
                String message = "Failed to read the URL: " + modelURL;
                if (mostRecentException != null) {
                    message += "\nReported error: " + mostRecentException.getMessage();
                    mostRecentException = null;
                }
                displayUserMessage(message);
                return;
            }
        }
        String completeModelString = modelString;
        final String finalModelString = completeModelString;
        final String codeExperimentsAndRestOfModel[] = new String[3];
        Runnable checkProceduresRunnable = new Runnable() {

            public void run() {
                try {
                    String extension;
                    if (finalModelString.contains(";; This is a 3D model that needs to be run by the 3D version of NetLogo")) {
                        extension = "nlogo3d";
                    } else {
                        extension = "nlogo";
                    }
                    String modelAsString = getCurrentSource(extension);
//                    System.out.println(modelAsString);
                    proceduresExperimentsAndRestOfModel(modelAsString, codeExperimentsAndRestOfModel);
                } catch (Exception ex) {
                    ex.printStackTrace(getErrorLog());
                }
            }

        };
        try {
            java.awt.EventQueue.invokeAndWait(checkProceduresRunnable);
        } catch(Exception ex) {
            ex.printStackTrace(getErrorLog());
        }
        String newCode = codeExperimentsAndRestOfModel[0];
        String updateURL = urlOnFirstLine(newCode);
        if (updateURL != null) {
            updateURL = removeBookmark(updateURL);
            // send URL to server to update user and session id
            String newUserGuid = getUserGuid(updateURL);
            String newSessionGuid = getSessionGuid(updateURL);
            String frozenModelGuid = getFrozenGuid(updateURL);
            if ((newUserGuid != null && newSessionGuid != null) || frozenModelGuid != null) {
                displayUserMessage("The model link will be sent to the Behaviour Composer. Your current model will be lost unless you save the URL in the Behaviour Composer before pressing OK.");
                if (newUserGuid != null) {
                    Settings.putPreference("userGuid", newUserGuid);
                }
                if (newSessionGuid != null) {
                    Settings.putPreference("sessionGuid", newSessionGuid);
                }
                printUserMessage("New model information sent. Please return to your browser page.");
                sendUpdateURLToBC(newUserGuid, newSessionGuid, frozenModelGuid);
                setProcedures("; Click 'Send model to NetLogo' to send the new model here.");
                userGuid = newUserGuid;
                sessionGuid = newSessionGuid;
                currentCode = null;
                currentRestOfModel = null;
                Settings.writePreferences();
                return;
            } else {
                displayUserMessage("To switch to another user or model you need to paste the Behaviour Composer URL into the code section on the first line. The URL should contain 'user' and 'session' parameters.");
            }
        }
        boolean codeEdited = 
                codeExperimentsAndRestOfModel[0] != null && currentCode != null && !newCode.equals(currentCode);
        boolean restOfModelEdited =
                codeExperimentsAndRestOfModel[2] != null && currentRestOfModel != null && !codeExperimentsAndRestOfModel[2].equals(currentRestOfModel);
        String procedureDifferences = "";
        String declarationDifferences = "";
        String widgetDifferences = "";
        String newInfoTabContents = "";
        if (restOfModelEdited) {
            String[] oldParts = currentRestOfModel.split("@#\\$#@#\\$#@\n");
            String[] newParts = codeExperimentsAndRestOfModel[2].split("@#\\$#@#\\$#@\n");
            widgetDifferences = collectWidgetDifferences(oldParts[1], newParts[1]);
            if (!oldParts[2].equals(newParts[2])) {
                newInfoTabContents = newParts[2];
            }
        }
        if (currentRestOfModel != null || currentCode != null) {
            // if both are null then is the NetLogo default (no procedures and lots of widgets -- many for Info tab)
            currentRestOfModel = codeExperimentsAndRestOfModel[2];
        }
        if (codeExperimentsAndRestOfModel[1] != null && !sessionChanged) {
            experimentsEdited = currentExperiments == null || !currentExperiments.equals(codeExperimentsAndRestOfModel[1]);
            currentExperiments = codeExperimentsAndRestOfModel[1];
        }
        sessionChanged = false;
        if (codeEdited) {
            procedureDifferences = collectProcedureDifferences(newCode);
            declarationDifferences = collectDeclarationDifferences(newCode);
        }
        if (procedureDifferences.isEmpty() && declarationDifferences.isEmpty()) {
            // changes must be in declarations not covered or comments
            codeEdited = false;
        }
        if (codeEdited || !widgetDifferences.isEmpty() || !newInfoTabContents.isEmpty()) {
            Boolean yesSendChanges = userYesOrNo("You edited your model. Do you want to send your changes to the Behaviour Composer?");
            if (yesSendChanges) {
                String errorResponse = sendDifferencesToBC(procedureDifferences, declarationDifferences, widgetDifferences, newInfoTabContents);
                Boolean yesTryAgain = true;
                while (errorResponse != null && yesTryAgain) {
                    yesTryAgain = userYesOrNo("Sorry but the following error was reported when sending the differences: " + errorResponse + "\nShould we try again?");
                    if (yesTryAgain) {
                        errorResponse = sendDifferencesToBC(procedureDifferences, declarationDifferences, widgetDifferences, newInfoTabContents);
                    }
                }
                if (errorResponse == null) {
                    // exit since the incoming model doesn't include these changes
                    printUserMessage("Changes sent to the Behaviour Composer. Please return to your browser page.");
                    currentCode = codeExperimentsAndRestOfModel[0];
                    currentRestOfModel = codeExperimentsAndRestOfModel[2];
                    return;
                }
            }
            String message = "Do you want to save your changes to the local file system?";
            if (!codeEdited) {
                message = "You edited your model. " + message;
            }
            boolean yesSaveToFileSystem = userYesOrNo(message);
            if (yesSaveToFileSystem) {
                displayUserMessage("Update from server canceled. Save your work and download again from the web page.");
                // assume the user really will save - don't bother him/her next time
                currentCode = codeExperimentsAndRestOfModel[0];
                currentRestOfModel = codeExperimentsAndRestOfModel[2];
                return;
            }
        } else if (experimentsEdited) {
            sendDifferencesToBC("", "", "", "");
            completeModelString = updateExperiments(completeModelString);
            experimentsEdited = false;
        }
        //	if (currentExperiments != null) {
        //	    String sessionGuid = Settings.getPreference("sessionGuid", null);
        //	    if (sessionGuid != null) {
        //		Settings.putPreference(EXPERIMENTS_OF_SESSION + sessionGuid, currentExperiments);
        //		System.out.println("saved " + EXPERIMENTS_OF_SESSION + sessionGuid);
        //	    }
        //	    completeModelString = insertExperiments(completeModelString);
        //	}
        Runnable openModelRunnable = new Runnable() {
            public void run() {

                try {
                    String directory = Settings.getPreference("BC2NetLogoDirectory", "");
                    String fileName = directory + "\\Model from Behaviour Composer.nlogo";
                    String extension = "nlogo";
                    if (finalModelString.contains(";; This is a 3D model that needs to be run by the 3D version of NetLogo")) {
                        fileName += "3d";
                        extension += "3d";
                    }
                    // openFromSource no longer works as it did in NetLogo 5.3 so
                    // workaround is to save to a file
                    PrintStream printStream = new PrintStream(fileName);
                    printStream.print(finalModelString.replace('\r', '\n'));
                    printStream.close();
                    App.app().open(fileName);
//                    App.app().openFromSource(fileName, finalModelString.replace('\r', '\n'));
//                    ConfigurableModelLoader loader = package$.MODULE$.basicLoader();
//                    Model model = loader.readModel(finalModelString.replace('\r', '\n'), extension).get();
//                    App.app().getProcedures().trim(); // what does this accomplish?
                    String modelAsString = getCurrentSource(extension);
//                    String modelAsString = loader.sourceString(model, extension).get();
                    proceduresExperimentsAndRestOfModel(modelAsString, codeExperimentsAndRestOfModel);
                    currentCode = codeExperimentsAndRestOfModel[0];
                    currentRestOfModel = codeExperimentsAndRestOfModel[2];
                }
                catch(Exception ex) {
                    ex.printStackTrace(getErrorLog());
                }
            }
        };
        try {
            java.awt.EventQueue.invokeAndWait(openModelRunnable);
        } catch(Exception ex) {
            ex.printStackTrace(getErrorLog());
        }
    }
    
    private static String getCurrentSource(String extension) {
        ModelSaver modelSaver = new ModelSaver(App.app(), null);
        Model currentModel = modelSaver.currentModel();
        ConfigurableModelLoader loader = package$.MODULE$.basicLoader();
//        System.out.println(loader.sourceString(currentModel, extension).get()); // currentModel.code() + 
        return loader.sourceString(currentModel, extension).get();
    }

    private static String updateExperiments(String modelString) {
        int experimentsStart = modelString.indexOf("<experiments>");
        if (experimentsStart < 0) {
            // warn?
            return modelString;
        }
        String endToken = "</experiments>";
        int endExperimentsToken = modelString.indexOf(endToken, experimentsStart);
        if (endExperimentsToken < 0) {
            // warn?
            return modelString;
        }
        return modelString.substring(0, experimentsStart) + currentExperiments + modelString.substring(endExperimentsToken+endToken.length());
    }

    private static String urlOnFirstLine(String newProcedures) {
        if (newProcedures == null || !newProcedures.contains("://")) {
            return null;
        }
        String[] split = newProcedures.split("\\n", 2);
        String updateURL = split[0];
        if (!updateURL.contains("://")) {
            return null;
        }
        if (!updateURL.isEmpty() && updateURL.startsWith(";")) {
            updateURL = updateURL.substring(1);
        }
        updateURL = updateURL.trim();
        return updateURL;
    }

    //    private static void addCommentsToEmptyModel() {
    //	setProcedures("; \n; If you want to import a model then paste the Behaviour Composer URL to the line above this.\n; Then click 'Send model to NetLogo'.");
    //    }

    private static void setProcedures(final String newCode) {
        Runnable addCommentsRunnable = new Runnable() {
            public void run() {

                try {
                    App.app().setProcedures(newCode);
                }
                catch(Exception ex) {
                    ex.printStackTrace(getErrorLog());
                }
            }
        };
        try {
            java.awt.EventQueue.invokeAndWait(addCommentsRunnable);
        } catch(Exception ex) {
            ex.printStackTrace(getErrorLog());
        }
    }

    private static String collectWidgetDifferences(String oldWidgetsSection, String newWidgetsSection) {
        // compares currentRestOfModel with newWidgets
        // widgets are separated by a blank line
        String oldWidgets[] = oldWidgetsSection.split("\n\n"); 
        String newWidgets[] = newWidgetsSection.split("\n\n");
        if (oldWidgets.length > newWidgets.length) {
            displayUserMessage("Unable to process edits that remove NetLogo interface items. Edits ignored. Sorry. If you want to create your own versions of the default buttons then 'set the-default-buttons-should-not-be-added true'");
            return "";
        }
        // assumes the same order
        String differences = "";
        for (int i = 0; i < oldWidgets.length; i++) {
            if (oldWidgets[i].equals(newWidgets[i])) {
                continue; // no difference
            } else {
                differences += widgetDifference(oldWidgets[i], newWidgets[i]);
            }
        }
        if (differences.isEmpty()) {
            return "";
        }
        return "<widgetDifferences>" + differences + "</widgetDifferences>";
    }

    private static String widgetDifference(String oldWidget, String newWidget) {
        String[] oldWidgetLines = oldWidget.split("\n");
        String[] newWidgetLines = newWidget.split("\n");
        oldWidgetLines = removeNetLogoSectionToken(oldWidgetLines);
        newWidgetLines = removeNetLogoSectionToken(newWidgetLines);
        if (!oldWidgetLines[0].equals(newWidgetLines[0])) {
            System.out.println("Widgets don't match: " + oldWidgetLines[0] + " and " + newWidgetLines[0]);
            return "";
        }
        String differences = "";
        // TODO: rename oldName attribute to oldIdentifier
        if (oldWidgetLines[0].equals("PLOT")) {
            differences += 
                    "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5] 
                            + "' oldAutoPlot='" + oldWidgetLines[12] + "' newAutoPlot='" + newWidgetLines[12]
                                    + "' oldLegendEnabled='" + oldWidgetLines[13] + "' newLegendEnabled='" + newWidgetLines[13] + "' >";
            // following should be in the same order as the text areas
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<plot-label oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            differences += "<x-axis-label oldValue='" + oldWidgetLines[6] + "' newValue='" + newWidgetLines[6] + "' />";
            differences += "<y-axis-label oldValue='" + oldWidgetLines[7] + "' newValue='" + newWidgetLines[7] + "' />";
            differences += "<minimum-x-value oldValue='" + oldWidgetLines[8] + "' newValue='" + newWidgetLines[8] + "' />";
            differences += "<minimum-y-value oldValue='" + oldWidgetLines[9] + "' newValue='" + newWidgetLines[9] + "' />";
            differences += "<maximum-x-value oldValue='" + oldWidgetLines[10] + "' newValue='" + newWidgetLines[10] + "' />";
            differences += "<maximum-y-value oldValue='" + oldWidgetLines[11] + "' newValue='" + newWidgetLines[11] + "' />";
            // oldWidgetLines[15] should be PENS
            String oldLegend = "";
            String newLegend = "";
            int index = 16;
            while (index < oldWidgetLines.length) {
                int endOfOldLegendName = oldWidgetLines[index].indexOf('"');
                if (endOfOldLegendName >= 0) {
                    endOfOldLegendName = oldWidgetLines[index].indexOf('"', endOfOldLegendName+1);
                }
                String oldLengendName = endOfOldLegendName < 0 ? "\"\"" : oldWidgetLines[index].substring(0, endOfOldLegendName+1);
                String[] oldPenParts = oldWidgetLines[index].split(" ");
                // fix the following to work for multiple PENS
                // 4th from last value -- since the first value can have space inside quotes find this from the other end
                String oldColorNumber = oldPenParts[oldPenParts.length-4]; 
                oldLegend += oldLengendName + " \"" + colorName(oldColorNumber) + "\" ";
                index++;
            }
            index = 16;
            while (index < newWidgetLines.length) {
                int endOfnewLegendName = newWidgetLines[index].indexOf('"');
                if (endOfnewLegendName >= 0) {
                    endOfnewLegendName = newWidgetLines[index].indexOf('"', endOfnewLegendName+1);
                }
                String newLengendName = endOfnewLegendName < 0 ? "\"\"" : newWidgetLines[index].substring(0, endOfnewLegendName+1);
                String[] newPenParts = newWidgetLines[index].split(" ");
                // fix the following to work for multiple PENS
                // 4th from last value -- since the first value can have space inside quotes find this from the other end
                String newColorNumber = newPenParts[newPenParts.length-4]; 
                newLegend += newLengendName + " \"" + colorName(newColorNumber) + "\" ";
                index++;
            }
            differences += "<legends oldValue='" + oldLegend + "' newValue='" + newLegend + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("MONITOR")) {
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += "<monitor-label oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";  
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<monitor-value oldValue='" + oldWidgetLines[6] + "' newValue='" + newWidgetLines[6] + "' />";
            differences += "<monitor-number-of-decimals oldValue='" + oldWidgetLines[7] + "' newValue='" + newWidgetLines[7] + "' />";
            differences += "<monitor-font-size oldValue='" + oldWidgetLines[8] + "' newValue='" + newWidgetLines[8] + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("CHOOSER")) {
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += "<chooser-variable oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";  
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<chooser-default-selection oldValue='" + oldWidgetLines[8] + "' newValue='" + newWidgetLines[8] + "' />";
            differences += "<chooser-choices oldValue='" + oldWidgetLines[7] + "' newValue='" + newWidgetLines[7] + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("TEXTBOX")) {
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + quoteEachLine(oldWidgetLines[5])  + "' >";
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<font-size oldValue='" + oldWidgetLines[6] + "' newValue='" + newWidgetLines[6] + "' />";  
            differences += "<text-color-number oldValue='" + integerString(oldWidgetLines[7]) + "' newValue='" + integerString(newWidgetLines[7]) + "' />";
            differences += "<transparent-background oldValue='" + int2TrueOrFalse(oldWidgetLines[8]) + "' newValue='" + int2TrueOrFalse(newWidgetLines[8]) + "' />";
            differences += "<text-initial-lines oldValue='" + quoteEachLine(oldWidgetLines[5]) + "' newValue='" + quoteEachLine(newWidgetLines[5]) + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("BUTTON")) {
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<button-label oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            // following needs special treat for button to work (netlogo-button is fine with this)
            differences += "<netlogo-code oldValue='" + oldWidgetLines[6] + "' newValue='" + newWidgetLines[6] + "' />";
            differences += "<keyboard-shortcut oldValue='" + oldWidgetLines[12] + "' newValue='" + newWidgetLines[12] + "' />";
            differences += "<repeat-forever oldValue='" + tOrNil2TrueOrFalse(oldWidgetLines[7]) + "' newValue='" + tOrNil2TrueOrFalse(newWidgetLines[7]) + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("SLIDER")) {
            // if only change is initial-value then ignore it
            if (oldWidgetLines[1].equals(newWidgetLines[1]) && 
                    oldWidgetLines[2].equals(newWidgetLines[2]) && 
                    oldWidgetLines[3].equals(newWidgetLines[3]) && 
                    oldWidgetLines[4].equals(newWidgetLines[4]) &&
                    oldWidgetLines[5].equals(newWidgetLines[5]) &&
                    // what is 6?
                    oldWidgetLines[7].equals(newWidgetLines[7]) && 
                    oldWidgetLines[8].equals(newWidgetLines[8]) && 
                    // don't check initial value
                    oldWidgetLines[10].equals(newWidgetLines[10]) && 
                    oldWidgetLines[12].equals(newWidgetLines[12]) &&
                    oldWidgetLines[13].equals( newWidgetLines[13])) {
                return differences;
            }    
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += "<variable-name oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            differences += "<initial-value oldValue='" + oldWidgetLines[9] + "' newValue='" + newWidgetLines[9] + "' />";
            differences += "<upper-left-corner oldValue='" + oldWidgetLines[1] + " " + oldWidgetLines[2] + "' newValue='" + newWidgetLines[1] + " " + newWidgetLines[2] + "' />";
            differences += "<lower-right-corner oldValue='" + oldWidgetLines[3] + " " + oldWidgetLines[4] + "' newValue='" + newWidgetLines[3] + " " + newWidgetLines[4] + "' />";
            differences += "<minimum-value oldValue='" + oldWidgetLines[7] + "' newValue='" + newWidgetLines[7] + "' />";
            differences += "<maximum-value oldValue='" + oldWidgetLines[8] + "' newValue='" + newWidgetLines[8] + "' />";
            differences += "<increment oldValue='" + oldWidgetLines[10] + "' newValue='" + newWidgetLines[10] + "' />";
            differences += "<units oldValue='" + oldWidgetLines[12] + "' newValue='" + newWidgetLines[12] + "' />";
            differences += "<horizontal oldValue='" + trueIfHorizontal(oldWidgetLines[13]) + "' newValue='" + trueIfHorizontal(newWidgetLines[13]) + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("INPUTBOX")) {
            // if only change is initial-value then ignore it
            if (oldWidgetLines[1].equals(newWidgetLines[1]) && 
                    oldWidgetLines[2].equals(newWidgetLines[2]) && 
                    oldWidgetLines[3].equals(newWidgetLines[3]) && 
                    oldWidgetLines[4].equals(newWidgetLines[4]) &&
                    oldWidgetLines[5].equals(newWidgetLines[5]) &&
                    // don't check initial value
                    oldWidgetLines[7].equals(newWidgetLines[7]) && 
                    oldWidgetLines[8].equals(newWidgetLines[8]) && 
                    oldWidgetLines[9].equals(newWidgetLines[9])) {
                return differences;
            }    
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += "<variable-name oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            differences += "<initial-value oldValue='" + oldWidgetLines[6] + "' newValue='" + newWidgetLines[6] + "' />";
            differences += "<upper-left-corner oldValue='" + oldWidgetLines[1] + " " + oldWidgetLines[2] + "' newValue='" + newWidgetLines[1] + " " + newWidgetLines[2] + "' />";
            differences += "<lower-right-corner oldValue='" + oldWidgetLines[3] + " " + oldWidgetLines[4] + "' newValue='" + newWidgetLines[3] + " " + newWidgetLines[4] + "' />";
            // include but ignore the slider attributes -- the text area indices will be correct by including this
            differences += "<minimum-value oldValue='" + "ignore" + "' newValue='" + "ignore" + "' />";
            differences += "<maximum-value oldValue='" + "ignore" + "' newValue='" + "ignore" + "' />";
            differences += "<increment oldValue='" + "ignore" + "' newValue='" + "ignore" + "' />";
            differences += "<units oldValue='" + "ignore" + "' newValue='" + "ignore" + "' />";
            differences += "<horizontal oldValue='" + "ignore" + "' newValue='" + "ignore" + "' />";
            differences += "<type-check oldValue='" + oldWidgetLines[9] + "' newValue='" + newWidgetLines[9] + "' />";
            differences += "<multi-line oldValue='" + oldWidgetLines[8] + "' newValue='" + newWidgetLines[8] + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("SWITCH")) {
            // if only change is initial-value then ignore it
            if (oldWidgetLines[1].equals(newWidgetLines[1]) && 
                    oldWidgetLines[2].equals(newWidgetLines[2]) && 
                    oldWidgetLines[3].equals(newWidgetLines[3]) && 
                    oldWidgetLines[4].equals(newWidgetLines[4]) &&
                    oldWidgetLines[5].equals(newWidgetLines[5])) {
                return differences;
            }   
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' oldName='" + oldWidgetLines[5]  + "' >";
            differences += "<variable-name oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            differences += "<initial-value oldValue='" + booleanFrom0Or1(oldWidgetLines[7]) + "' newValue='" + booleanFrom0Or1(newWidgetLines[7]) + "' />";
            differences += "<upper-left-corner oldValue='" + oldWidgetLines[1] + " " + oldWidgetLines[2] + "' newValue='" + newWidgetLines[1] + " " + newWidgetLines[2] + "' />";
            differences += "<lower-right-corner oldValue='" + oldWidgetLines[3] + " " + oldWidgetLines[4] + "' newValue='" + newWidgetLines[3] + " " + newWidgetLines[4] + "' />";
            differences += "</widgetDifference>";
        } else if (oldWidgetLines[0].equals("GRAPHICS-WINDOW")) {
            if (oldWidgetLines[1] != newWidgetLines[1] || oldWidgetLines[2] != newWidgetLines[2]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-LOCATION'>";
                differences += "<upper-left-world-x oldValue='" + oldWidgetLines[1] + "' newValue='" + newWidgetLines[1] + "' />";
                differences += "<upper-left-world-y oldValue='" + oldWidgetLines[2] + "' newValue='" + newWidgetLines[2] + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[7] != newWidgetLines[7]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-PATCH-SIZE'>";
                differences += "<initial-patch-size oldValue='" + oldWidgetLines[7] + "' newValue='" + newWidgetLines[7] + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[14] != newWidgetLines[14] || oldWidgetLines[15] != newWidgetLines[15]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-WRAP'>";
                differences += "<type-of-geometry oldValue='" + geometryCode(oldWidgetLines[14], oldWidgetLines[15]) +
                        "' newValue='" + geometryCode(newWidgetLines[14], newWidgetLines[15]) + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[17] != newWidgetLines[17] || oldWidgetLines[18] != newWidgetLines[18] ||
                    oldWidgetLines[19] != newWidgetLines[19] || oldWidgetLines[20] != newWidgetLines[20]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-SIZE'>";
                differences += "<minimum-world-x oldValue='" + oldWidgetLines[17] + "' newValue='" + newWidgetLines[17] + "' />";
                differences += "<maximum-world-x oldValue='" + oldWidgetLines[18] + "' newValue='" + newWidgetLines[18] + "' />";
                differences += "<minimum-world-y oldValue='" + oldWidgetLines[19] + "' newValue='" + newWidgetLines[19] + "' />";
                differences += "<maximum-world-y oldValue='" + oldWidgetLines[20] + "' newValue='" + newWidgetLines[20] + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[21] != newWidgetLines[21]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-VIEW-UPDATE'>";
                differences += "<tick-based-updates oldValue='" + !booleanFrom0Or1(oldWidgetLines[21]) +
                        "' newValue='" + !booleanFrom0Or1(newWidgetLines[21]) + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[23] != newWidgetLines[23]) {
                differences += "<widgetDifference type='GRAPHICS-SHOW-TICK-COUNTER'>";
                differences += "<frame-rate oldValue='" + !booleanFrom0Or1(oldWidgetLines[23]) +
                        "' newValue='" + !booleanFrom0Or1(newWidgetLines[23]) + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[24] != newWidgetLines[24]) {
                differences += "<widgetDifference type='GRAPHICS-TICK-COUNTER-LABEL'>";
                differences += "<frame-rate oldValue='" + oldWidgetLines[24] +
                        "' newValue='" + newWidgetLines[24] + "' />";
                differences += "</widgetDifference>";
            }
            if (oldWidgetLines[25] != newWidgetLines[25]) {
                differences += "<widgetDifference type='GRAPHICS-WINDOW-FRAME-RATE'>";
                differences += "<frame-rate oldValue='" + oldWidgetLines[25] +
                        "' newValue='" + newWidgetLines[25] + "' />";
                differences += "</widgetDifference>";
            }
        } else if (oldWidgetLines[0].equals("OUTPUT")) {
            differences += "<widgetDifference type='" + oldWidgetLines[0] + "' >";
            differences += widgetCorners(oldWidgetLines, newWidgetLines);
            differences += "<font-size oldValue='" + oldWidgetLines[5] + "' newValue='" + newWidgetLines[5] + "' />";
            differences += "</widgetDifference>";
        } else {
            logError("No support for edits of " + oldWidgetLines[0]);
        }
        // & is an escape character in XML but not using it as such
        return differences.replace("&", "&amp;");
    }

    //    private static String collectInfoDifferences(String oldInfo, String newInfo) {
    //	// nothing needs the old info so just send the new version
    //	return "<infoDifference>" +
    //		"<old>" +
    //		createCDATASection(oldInfo) +
    //		"</old>" +
    //		"<new>" +
    //		createCDATASection(newInfo) +
    //		"</old>" +
    //		"</infoDifference>";
    //    }

    private static int geometryCode(String horizontalWrap, String verticalWrap) {
        if (horizontalWrap.equals("1")) {
            if (verticalWrap.equals("1")) {
                return 1;
            } else {
                return 2;
            }
        } else if (verticalWrap.equals("1")) {
            return 3;
        } else {
            return 4;
        }	
    }

    private static String[] removeNetLogoSectionToken(String[] lines) {
        int tokens = 0;
        while (lines[tokens].equals("@#$#@#$#@")) {
            tokens++;	    
        }
        if (tokens == 0) {
            return lines;
        } else {
            int newLength = lines.length-tokens;
            String remainingLines[] = new String[newLength];
            for (int i = 0; i < newLength; i++) {
                remainingLines[i] = lines[i+tokens];
            }
            return remainingLines;
        }
    }

    private static boolean booleanFrom0Or1(String string) {
        return string.equals("0");
        // no idea why NetLogo consider 0 to the true
    }

    private static String integerString(String string) {
        try {
            // round to integer (since 55.0 confuses NetLogo instead of 55)
            return Integer.toString((int) Double.parseDouble(string));
        }  catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trueIfHorizontal(String string) {
        if (string.equals("HORIZONTAL")) {
            return "true";
        } else {
            return "false";
        }
    }

    private static String tOrNil2TrueOrFalse(String string) {
        if (string.equals("NIL")) {
            return "false";
        } else {
            return "true";
        }
    }

    private static String int2TrueOrFalse(String string) {
        if (string.equals("0")) {
            return "false";
        } else {
            return "true";
        }
    }

    private static String quoteEachLine(String string) {
        String[] lines = string.split("\\\\n");
        String result = "";
        for (int i = 0; i < lines.length; i++) {
            result += '"' + lines[i] + '"';
            if (i+1 != lines.length) {
                // not the last one
                result += ' ';
            }
        }
        return result;
    }

    private static String widgetCorners(String[] oldWidgetLines, String[] newWidgetLines) {
        String xml = "";
        xml += "<upper-left-corner-x oldValue='" + oldWidgetLines[1] + "' newValue='" + newWidgetLines[1] + "' />";
        xml += "<upper-left-corner-y oldValue='" + oldWidgetLines[2] + "' newValue='" + newWidgetLines[2] + "' />";
        xml += "<lower-right-corner-x oldValue='" + oldWidgetLines[3] + "' newValue='" + newWidgetLines[3] + "' />";
        xml += "<lower-right-corner-y oldValue='" + oldWidgetLines[4] + "' newValue='" + newWidgetLines[4] + "' />";
        return xml;	
    }

    private static String colorName(String number) {
        // a hack to make an inverse of colorNumber in NetLogoModel
        if (number.equalsIgnoreCase("-16777216")) {
            return "black";
        } else if (number.equalsIgnoreCase("-11221820")) {
            return "cyan";
        } else if (number.equalsIgnoreCase("-1")) {
            return "white";
        } else if (number.equalsIgnoreCase("-2674135")) {
            return "red";
        } else if (number.equalsIgnoreCase("-10899396")) {
            return "green";
        } else if (number.equalsIgnoreCase("-13345367")) {
            return "blue";
        } else if (number.equalsIgnoreCase("-7500403")) {
            return "gray";
        } else if (number.equalsIgnoreCase("-955883")) {
            return "orange"; 
        } else if (number.equalsIgnoreCase("-6459832")) {
            return "brown"; 
        } else if (number.equalsIgnoreCase("-1184463")) {
            return "yellow";
        } else if (number.equalsIgnoreCase("-13840069")) {
            return "lime";
        } else if (number.equalsIgnoreCase("-14835848")) {
            return "turquoise";
        } else if (number.equalsIgnoreCase("-13791810")) {
            return "sky"; 
        } else if (number.equalsIgnoreCase("-8630108")) {
            return "violet";
        } else if (number.equalsIgnoreCase("-5825686")) {
            return "magenta";
        } else if (number.equalsIgnoreCase("-2064490")) {
            return "pink";
        } else {
            try {
                Integer.parseInt(number);
                return number;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    protected static String collectProcedureDifferences(String newProcedures) {
        String differences = collectProcedureDifferences("\nto ", newProcedures, currentCode);
        differences += collectProcedureDifferences("\nto-report ", newProcedures, currentCode);
        if (differences.isEmpty()) {
            return differences;
        }
        differences = "<procedureDifferences>" + differences + "</procedureDifferences>";
        return differences;
    }

    private static String sendDifferencesToBC(
            String procedureDifferences, String declarationDifferences, String widgetDifferences, String newInfoTabContents) {
        try {
            String parameters = "procedureDifferences=" + URLEncoder.encode(procedureDifferences, "UTF-8");
            parameters += "&declarationDifferences=" + URLEncoder.encode(declarationDifferences, "UTF-8");    
            parameters += "&widgetDifferences=" + URLEncoder.encode(widgetDifferences, "UTF-8");
            parameters += "&infoTab=" + URLEncoder.encode(newInfoTabContents, "UTF-8");
            parameters += "&sessionGuid=" + URLEncoder.encode(sessionGuid, "UTF-8");
            parameters += "&userGuid=" + URLEncoder.encode(userGuid, "UTF-8");
            if (currentExperiments != null) {
                parameters += "&experiments=" + URLEncoder.encode(currentExperiments, "UTF-8");
            }
            excutePost(domain + "/NetLogoPost", parameters);
            return null;
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
            return e.getMessage();
        }
    }

    private static boolean sendUpdateURLToBC(String newUserGuid, String newSessionGuid, String frozenModelGuid) {
        try {
            String parameters = "userGuid=" + URLEncoder.encode(userGuid, "UTF-8");
            parameters += "&sessionGuid=" + URLEncoder.encode(sessionGuid, "UTF-8");
            if (frozenModelGuid != null) {
                parameters += "&frozen=" + URLEncoder.encode(frozenModelGuid, "UTF-8");
            } else {
                parameters += "&newUserGuid=" + URLEncoder.encode(newUserGuid, "UTF-8");
                parameters += "&newSessionGuid=" + URLEncoder.encode(newSessionGuid, "UTF-8");
            }
            String response = excutePost(domain + "/NetLogoPost", parameters);
            if (!response.isEmpty()) {
                reopenChannel(response);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace(getErrorLog());
            return false;
        }
    }

    private static String collectProcedureDifferences(String toOrToReport, String newProcedures, String oldBodys) {
        String differences = "";
        int toOrToReportIndex = newProcedures.indexOf(toOrToReport);
        if (toOrToReportIndex < 0) {
            // no more procedures of type toOrToReport
            return differences;
        }
        int[] procedureNameStartEnd = getProcedureName(newProcedures, toOrToReportIndex, toOrToReport);
        if (procedureNameStartEnd != null) {
            String procedureName = newProcedures.substring(procedureNameStartEnd[0], procedureNameStartEnd[1]);
            int[] newProcedureStartEnd = getProcedureBody(newProcedures, toOrToReportIndex+1);
            if (newProcedureStartEnd == null) {
                return differences;
            }
            int oldDefinitionStart = oldBodys.indexOf(toOrToReport + procedureName + (char) procedureNameStartEnd[2]);
            if (oldDefinitionStart < 0) {
                // only in the new procedures
                String newProcedure = getProcedure(newProcedures, toOrToReportIndex+1);
                differences += "<newProcedure>" + createCDATASection(newProcedure) + "</newProcedure>";
            } else {
                int[] oldBodyStartEnd = getProcedureBody(oldBodys, oldDefinitionStart+1);
                if (oldBodyStartEnd != null) {
                    String oldBody = oldBodys.substring(oldBodyStartEnd[0], oldBodyStartEnd[1]);
                    String newProcedure = newProcedures.substring(newProcedureStartEnd[0], newProcedureStartEnd[1]);
                    if (!oldBody.equals(newProcedure)) {
                        differences += "<procedureChanged procedureName='" + procedureName.trim() + "'>"
                                + "<new>" + createCDATASection(newProcedure) + "</new>"
                                + "<old>" + createCDATASection(oldBody) + "</old>"
                                + "</procedureChanged>";
                    }
                }
            }
            differences += collectProcedureDifferences(toOrToReport, newProcedures.substring(newProcedureStartEnd[1]), oldBodys);
        }
        return differences;
    }

    private static String collectDeclarationDifferences(String newCode) {
        final String[] declarations = {"extensions", "breed"}; // maybe more
        String differences = "";
        for (String declaration : declarations) {
            differences += collectDeclarationDifferences(declaration, newCode, currentCode);
        }
        if (differences.isEmpty()) {
            return differences;
        } else {
            return "<declarationDifferences>" + differences + "</declarationDifferences>";
        }
    }

    private static String collectDeclarationDifferences(String declaration, String newCode, String oldCode) {
        String differences = "";
        int[] newDeclarationStartEnd = nextDeclaration(declaration, newCode);
        if (newDeclarationStartEnd == null) {
            return differences;
        }
        int[] oldDeclarationStartEnd = nextDeclaration(declaration, oldCode);
        String newDeclaration = newCode.substring(newDeclarationStartEnd[0], newDeclarationStartEnd[1]);
        differences += "<declarationChanged>"
                + "<new>" + createCDATASection(newDeclaration) + "</new>";
        if (oldDeclarationStartEnd != null) {
            String oldDeclaration = oldCode.substring(oldDeclarationStartEnd[0], oldDeclarationStartEnd[1]);
            if (newDeclaration.equals(oldDeclaration)) {
                return "";
            }
            differences += "<old>" + createCDATASection(oldDeclaration) + "</old>";
            oldCode = oldCode.substring(oldDeclarationStartEnd[1]);
        }
        differences += "</declarationChanged>";
        differences += collectDeclarationDifferences(declaration, newCode.substring(newDeclarationStartEnd[1]), oldCode);
        return differences;
    }

    protected static int[] nextDeclaration(String declaration, String code) {
        int newDeclarationStart = code.indexOf(declaration);
        if (newDeclarationStart < 0) {
            // no more declarations
            return null;
        }
        int newDeclarationEnd = code.indexOf(']', newDeclarationStart);
        if (newDeclarationEnd < 0) {
            return null;
        }
        newDeclarationEnd++;
        int startEnd[] = new int[2];
        startEnd[0] = newDeclarationStart;
        startEnd[1] = newDeclarationEnd;
        return startEnd;
    }

    private static String getProcedure(String procedures, int toOrToReportIndex) {
        int endIndex = procedures.indexOf("\nend\n", toOrToReportIndex);
        if (endIndex < 0) {
            return null;
        } else {
            return procedures.substring(toOrToReportIndex, endIndex+5);
        }
    }

    private static int[] getProcedureBody(String procedures, int toOrToReportIndex) {
        int startEnd[] = new int[2];
        int endIndex = procedures.indexOf("\nend\n", toOrToReportIndex);
        if (endIndex < 0) {
            return null;
        } else {
            int bodyStart = procedures.indexOf('\n', toOrToReportIndex);
            if (bodyStart < 0) {
                return null;
            }
            if (bodyStart == endIndex) {
                return null;
            }
            startEnd[0] = bodyStart+1;
            startEnd[1] = endIndex;
            return startEnd;
        }
    }

    private static int[] getProcedureName(String procedures, int commandIndex, String toOrToReport) {
        // returns start and end indices and whether it is terminated by a space or new line
        int startEnd[] = new int[3];
        int nameStart = commandIndex+toOrToReport.length();
        int nameEnd = procedures.indexOf('\n', nameStart);
        if (nameEnd < 0) {
            nameEnd = procedures.indexOf(' ', nameStart);
            if (nameEnd < 0) {
                return null;
            }
            startEnd[2] = (int) ' ';
        } else {
            startEnd[2] = (int) '\n';
        }
        startEnd[0] = nameStart;
        startEnd[1] = nameEnd;
        return startEnd;
    }

    protected static void proceduresExperimentsAndRestOfModel(String entireModel, String[] experimentsAndRestOfModel) {
        int indexOfRestOfModel = entireModel.indexOf("@#$#@#$#@");
        if (indexOfRestOfModel < 0) {
            // report problem?
            return;
        }
        // trim for comparison with getProcedures()
        experimentsAndRestOfModel[0] = entireModel.substring(0, indexOfRestOfModel).trim();
        String modelWithoutProcedures = entireModel.substring(indexOfRestOfModel);
        int experimentsStart = modelWithoutProcedures.indexOf("<experiments>");
        final String endTag = "</experiments>";
        int experimentsEnd = -1;
        if (experimentsStart >= 0) {
            experimentsEnd = modelWithoutProcedures.indexOf(endTag, experimentsStart);
        }
        if (experimentsEnd < 0) {
            experimentsAndRestOfModel[1] = null;
            experimentsAndRestOfModel[2] = modelWithoutProcedures;
        } else {
            experimentsEnd += endTag.length();
            experimentsAndRestOfModel[1] = modelWithoutProcedures.substring(experimentsStart, experimentsEnd);
            experimentsAndRestOfModel[2] = modelWithoutProcedures.substring(0, experimentsStart) +
                                           modelWithoutProcedures.substring(experimentsEnd);
        }
    }

    static public String createCDATASection(String string) {
        // TODO: substitute more generally than this
        // at least ¬ causes the following when in XML
        // org.apache.xerces.impl.io.MalformedByteSequenceException: Invalid byte 1 of 1-byte UTF-8 sequence.
        return "<![CDATA[" + (string == null ? "no description" : string.replace("¬","&not;")) + "]]>";
    }

    public static String excutePost(String targetURL, String urlParameters) throws Exception {
        // based upon http://www.xyzws.com/Javafaq/how-to-use-httpurlconnection-post-data-to-web-server/139
        URL url;
        HttpURLConnection connection = null;  
        try {
            // Create connection
            url = new URL(targetURL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", "" + Integer.toString(urlParameters.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");  
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            //Send request
            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            wr.writeBytes(urlParameters);
            wr.flush();
            wr.close();
            // Get Response	
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            StringBuffer response = new StringBuffer(); 
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            return response.toString();
        } catch (Exception e) {
            if (userYesOrNo("Failed to contact the server. Differences not yet copied to the Behaviour Composer. Do you want to try again?")) {
                return excutePost(targetURL, urlParameters);
            } else {
                displayUserMessage("You will need to copy and paste changes. Error message: " + e.getMessage());
                e.printStackTrace(getErrorLog());
                return null;
            }
        } finally {
            if (connection != null) {
                connection.disconnect(); 
            }
        }
    }

    public static int firstDifference(String a, String b) {
        int stop = Math.min(a.length(), b.length());
        for (int i = 0; i < stop; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return -1;
    }

    public static String removeBookmark(String url) {
        int sharp = url.indexOf('#');
        if (sharp >= 0) {
            return url.substring(0, sharp);
        }
        return url;
    }

    public static boolean isWaitingForNewConnection() {
        return waitingForNewConnection;
    }

    public static void setWaitingForNewConnection(boolean waitingForNewConnection) {
        BC2NetLogo.waitingForNewConnection = waitingForNewConnection;
    }

    public static PrintStream getErrorLog() {
        String directory = "."; // Settings.getPreference("BC2NetLogoDirectory", null);
        //	if (directory == null) {
        //	    return null;
        //	}
        if (errorLog == null) {
            try {
                errorLog = new PrintStream(directory + "/errors/" + new Date().getTime() + ".log");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        return errorLog;
    }

    public static void logError(String message) {
        PrintStream stream = getErrorLog();
        stream.print(message);
    }

}