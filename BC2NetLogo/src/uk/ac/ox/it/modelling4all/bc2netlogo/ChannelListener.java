/**
 * 
 */
package uk.ac.ox.it.modelling4all.bc2netlogo;

import edu.gvsu.cis.masl.channelAPI.ChannelService;

/**
 * @author Ken Kahn
 *
 */
public class ChannelListener implements ChannelService {

//    private boolean communicationErrorHandled = false;

    @Override
    public void onOpen() {
	// nothing special to do
    }

    @Override
    public void onMessage(String message) {
	try {
	    BC2NetLogo.processMessage(message);
	} catch (Exception e) {
	    System.err.println("Error processing message. " + message);
	    e.printStackTrace();
	}
    }

    @Override
    public void onClose() {
//	BC2NetLogo.displayUserMessage("Communication channel with server closed.");
    }

    @Override
    public void onError(Integer errorCode, String description) {
	if (BC2NetLogo.isWaitingForNewConnection()) {
	    return;
	}
	BC2NetLogo.setWaitingForNewConnection(true);
	String message = "Encountered an error communicating with the server. Please wait while a new communication channel is established. Error code=" 
                         + errorCode
                         + ". Error=" + description;
//	System.out.println("BC2NetLogo error: " + message); // debug this
	BC2NetLogo.printUserMessage(message);
	BC2NetLogo.getConnectionToServer(true);
//	if (errorCode != 401 && errorCode != 500) {
//	    // perhaps a temporary problem
//	    if (communicationErrorHandled) {
//		return;
//	    }
//	    BC2NetLogo.displayUserMessage(message + " Will keep trying but you may need to save your work and restart.");
//	    communicationErrorHandled = true;
//	    return;
//	}
	// instead of restarting this will attempt to get a new channel
//	if (BC2NetLogo.userYesOrNo("Encountered an error communicating with the server. Do you want to save any edits you have made before BC2NetLogo is restarted.")) {
//	    BC2NetLogo.displayUserMessage("Either copy and paste your edits or click on the 'File' then 'Save' menu item to save your work (can be restored using 'File' then 'Open'). Exit and restart when you are finished.");
//	    return;
//	}
//	// give the installer a chance to detect this and respond.
//	System.err.println(message);
//	BC2NetLogo.displayUserMessage("BC2NetLogo will attempt to restart. If this fails we recommend you create a frozen version of your model in the Behaviour Composer, close that tab, and use the frozen model URL in the advanced settings area after restarting BC2NetLogo.");
//	try {
//	    synchronized(this) {
//		wait(5000);
//	    }
//	} catch (InterruptedException e) {
//	    e.printStackTrace();
//	} 
//	System.exit(0);
    }

}
