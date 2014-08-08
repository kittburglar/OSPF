import java.io.*;
import java.net.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.*;
import java.nio.*;

public class router {

	int routerID = 0;
	InetAddress nseHost = null;
	int nsePort = 0;
	int routerPort = 0;
	byte[] circuitDatabase;
	DatagramSocket routerSocket;
	Writer routerWriter;
	int nbrLink;
	int nbrRouter = 5;
	ArrayList<int[]> tempQueue = new ArrayList<int[]>();
	ArrayList<int[]> neighbours = new ArrayList<int[]>();
	ArrayList<int[]> allNeighbours = new ArrayList<int[]>();
	ArrayList<int[]> linkStateDatabase = new ArrayList<int[]>();
	ArrayList<int[]> routingInformationBase = new ArrayList<int[]>();
	int[] nbrLinks = new int [5];
	int[] nextHops = new int [5];
	int[] dist = new int[5];
	int[] previous = new int[5];
	PriorityQueue<int[]> queue = new PriorityQueue<int[]>(10, new Comparator<int[]>() {
			public int compare(int[] entry1, int[] entry2){
			return entry1[1] - entry2[1];

			}
			});

	//Constructor
	router(String routerID2, String nseHost2, String nsePort2, String routerPort2){
		try{
			routerID = Integer.parseInt(routerID2);
			nsePort = Integer.parseInt(nsePort2);
			nseHost = InetAddress.getByName(nseHost2);
			routerPort = Integer.parseInt(routerPort2);
			routerSocket = new DatagramSocket(Integer.parseInt(routerPort2));

		}catch(Exception e){
			System.exit(1);
		}

	}


	//Send inital packets 
	public void sendInit() throws Exception {
		try{
			ByteBuffer buffer = ByteBuffer.allocate(4);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putInt(routerID);
			byte[] senderData = new byte[1024];
			senderData = buffer.array();

			DatagramPacket sendPacket = new DatagramPacket(senderData, senderData.length, nseHost, nsePort);
			routerSocket.send(sendPacket);

		}catch(Exception e){
			System.err.println("send error!");
			System.exit(1);
		}
	}


	//Receive circuit database
	public void receiveCDB() throws Exception {
		try{
			byte[] circuitDatabase = new byte[1024];
			DatagramPacket circuitDatabasePacket = new DatagramPacket(circuitDatabase, circuitDatabase.length);
			routerSocket.receive(circuitDatabasePacket);
			this.circuitDatabase = circuitDatabasePacket.getData();
		}catch(Exception e){
			System.err.println("failedprintRIB(); to receive circuit database");
			System.exit(1);
		}
	}


	//Receive circuit database to be run by a seperate thread
	private class receiveCDB implements Runnable {
		public void run(){
			byte[] cdb = new byte[1024];
			DatagramPacket circuitDatabasePacket = new DatagramPacket(cdb, cdb.length);
			try{
				routerSocket.receive(circuitDatabasePacket);
				circuitDatabase = circuitDatabasePacket.getData();
			}catch(Exception e){
				System.err.println("failed to receive circuit database");
				System.exit(1);
			}
		}
	}

	//Parse the received circuit database and store the information in our Link State Database.
	//We also need to write to our log files since the Link State Database changes.
	public void parseCDB() throws Exception {
		try{
			ByteBuffer buffer = ByteBuffer.wrap(this.circuitDatabase);			
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			int nbrLink = buffer.getInt();
			this.nbrLink = nbrLink;
			for(int i=0; i<nbrLink ; i++){

				int link = buffer.getInt();
				int cost = buffer.getInt();
				int[] linkCost = new int[3];
				linkCost[0] = routerID;
				linkCost[1] = link;
				linkCost[2] = cost;
				linkStateDatabase.add(linkCost);
				findNeighbours();
				dijkstraAlgorithm(routerID);
				findNextHops();
				printLinkStateDatabase();
				printRIB();
			}
		}catch(Exception e){
			System.err.println("failure to parse circuit database");
		}
	}

	//Send hello packets to neighbours
	public void sendHelloPackets() throws Exception{
		Thread receiveHelloPacketsThread = new Thread(new receiveHelloPackets());
		receiveHelloPacketsThread.start();
		try{
			for(int i = 0; i < nbrLink; i++){
				ByteBuffer buffer = ByteBuffer.allocate(8);
				buffer.order(ByteOrder.LITTLE_ENDIAN);
				buffer.putInt(routerID);
				buffer.putInt(linkStateDatabase.get(i)[1]);
				byte[] senderData = new byte[1024];
				senderData = buffer.array();
				DatagramPacket sendPacket = new DatagramPacket(senderData, senderData.length, nseHost, nsePort);
				routerSocket.send(sendPacket);

			}
			receiveHelloPacketsThread.join();
		}catch(Exception e){
			System.err.println("send error!");
			System.exit(1);
		}

	}


	//Received hello packets from our neighbours. Going to run on a seperate thread.
	private class receiveHelloPackets implements Runnable {
		public void run(){
			for(int i = 0; i < nbrLink; i++){
				try{
					byte[] helloData = new byte[1024];
					DatagramPacket helloDataPacket = new DatagramPacket(helloData, helloData.length);
					routerSocket.receive(helloDataPacket);
					System.out.println("Received Hello Packet");
					ByteBuffer buffer = ByteBuffer.wrap(helloDataPacket.getData());
					buffer.order(ByteOrder.LITTLE_ENDIAN);
					int helloRouterID = buffer.getInt();
					int helloLinkID = buffer.getInt();
					int[] neighbourInfo = new int[2];
					neighbourInfo[0] = helloRouterID;
					neighbourInfo[1] = helloLinkID;
					neighbours.add(neighbourInfo);
				}catch(Exception e){
					System.err.println("failed to receive circuit database");
					System.exit(1);
				}
			}

		}
	}

	//Helper function to determine if a router is out neighbour.
	private boolean checkNeighbour(int routerID){
		for(int i = 0; i < neighbours.size(); i++){
			if(routerID == neighbours.get(i)[0]){
				return true;
			}
		}
		return false;
	}

	//Send our inital LSPDU to our neighbours
	public void sendLinkStatePackets() throws Exception {
		Thread receiveLinkStatePacketsThread = new Thread(new receiveLinkStatePackets());
		receiveLinkStatePacketsThread.start();
		int sizeofDatabase = linkStateDatabase.size();
		try{
			for(int i = 0; i < nbrLink; i++){
				for(int j = 0; j < sizeofDatabase; j++){
					ByteBuffer buffer = ByteBuffer.allocate(20);
					buffer.order(ByteOrder.LITTLE_ENDIAN);
					//sender
					buffer.putInt(routerID);
					//router_id
					buffer.putInt(routerID);
					//link_id
					buffer.putInt(linkStateDatabase.get(j)[1]);
					//cost
					buffer.putInt(linkStateDatabase.get(j)[2]);
					//via		
					buffer.putInt(linkStateDatabase.get(i)[1]);
					byte[] senderData = new byte[1024];
					senderData = buffer.array();
					DatagramPacket sendPacket = new DatagramPacket(senderData, senderData.length, nseHost, nsePort);
					routerSocket.send(sendPacket);
				}
			}
		}catch(Exception e){
			System.err.println("send error!");
			System.exit(1);
		}
	}

	//Send our LSPDU to our neighbours
	public void sendLinkStatePackets2(int source) throws Exception {
		int sizeofDatabase = linkStateDatabase.size();
		try{
			for(int i = 0; i < neighbours.size(); i++){
				for(int j = 0; j < sizeofDatabase; j++){
					ByteBuffer buffer = ByteBuffer.allocate(20);
					buffer.order(ByteOrder.LITTLE_ENDIAN);
					//sender
					buffer.putInt(routerID);
					//router_id
					buffer.putInt(linkStateDatabase.get(j)[0]);
					//link_id
					buffer.putInt(linkStateDatabase.get(j)[1]);
					//cost
					buffer.putInt(linkStateDatabase.get(j)[2]);
					//via           
					buffer.putInt(neighbours.get(i)[1]);
					byte[] senderData = new byte[1024];
					senderData = buffer.array();

					DatagramPacket sendPacket = new DatagramPacket(senderData, senderData.length, nseHost, nsePort);
					if(neighbours.get(i)[1] != source){
						routerSocket.send(sendPacket);
					}

				}
			}
		}catch(Exception e){
			System.err.println("send error2!");
			System.exit(1);
		}
	}

	//If the LSPDU is unique rturn true, otherwise return false.
	//Useful since we do not want doubles in out Link State Database.
	private boolean newEntry(int routerID, int linkID, int cost){
		for(int i=0; i < linkStateDatabase.size(); i++){
			if((routerID == linkStateDatabase.get(i)[0]) &&
					(linkID == linkStateDatabase.get(i)[1]) &&
					(cost == linkStateDatabase.get(i)[2])){
				return false;
			}
		}	
		return true;
	}

	//Receive LSPDU packets. This function is going to run on a seperate thread.
	private class receiveLinkStatePackets implements Runnable {
		public void run(){
			while(true){
				try{
					byte[] linkStateData = new byte[1024];
					DatagramPacket linkStateDataPacket = new DatagramPacket(linkStateData, linkStateData.length);
					routerSocket.receive(linkStateDataPacket);
					//System.out.println("Received LSPDU Packets");
					ByteBuffer buffer = ByteBuffer.wrap(linkStateDataPacket.getData());
					buffer.order(ByteOrder.LITTLE_ENDIAN);
					//sender
					int sender = buffer.getInt();
					//router_id
					int routerID2 = buffer.getInt();
					//link_id
					int linkID = buffer.getInt();
					//cost
					int cost = buffer.getInt();
					//via
					int via = buffer.getInt();
					int[] linkCost = new int[3];
					linkCost[0] = routerID2;
					linkCost[1] = linkID;
					linkCost[2] = cost;
					if(newEntry(routerID2, linkID, cost)){
						System.out.println("Received new LSPDU Information");
						linkStateDatabase.add(linkCost);
						findNeighbours();
						dijkstraAlgorithm(routerID);
						findNextHops();
						sendLinkStatePackets2(via);
						printLinkStateDatabase();
						printRIB(); 
					}
				}catch(Exception e){
					System.err.println("failed to receive link state data");
					System.exit(1);
				}
			}
		}
	}

	//Sort the Link State Database
	public void sortLinkStateDatabase() throws Exception {
		Collections.sort(linkStateDatabase, new Comparator<int[]>(){
				public int compare(int[] l1, int[] l2) {
				return Integer.valueOf(l1[0]).compareTo(l2[0]);
				}
				});

	}	

	//Find the number of links connected to this router.
	public void findNumberofLinks() throws Exception {
		for(int i = 0; i< linkStateDatabase.size(); i++){
			nbrLinks[linkStateDatabase.get(i)[0] - 1]++;
		}
	}

	//Write the Link State Database to the log file.
	public void printLinkStateDatabase() throws Exception {
		String output = "";
		try{
			sortLinkStateDatabase();
			findNumberofLinks();
			//String output = "";
			output = "# Topology database\n";
			int j = 0;
			int k = 1;
			for(int i = 0; i < linkStateDatabase.size(); i++){
				if(j == nbrLinks[k - 1]){
					j = 0;
					k++;
				}
				if(j == 0){
					while(true){
						if(nbrLinks[k - 1] == 0){
							k++;
						}
						else{
							output = output + "R" + routerID + " -> R" + k + " nbr link " + nbrLinks[k - 1] + "\n";
							break;
						}
					}
				}
				output = output + "R" + routerID + " -> " ;
				output = output + "R" + linkStateDatabase.get(i)[0];
				output = output + " link " + linkStateDatabase.get(i)[1];
				output = output + " cost " + linkStateDatabase.get(i)[2] + "\n";
				j++;
			}
			Arrays.fill(nbrLinks, 0);
			System.out.println("Writing to Log file");
			routerWriter.write(output);
			routerWriter.flush();
		} catch (Exception e){
			System.err.println("failed to print linkstate datatebase");
			System.exit(1);
		}



	}

	//Checks if a router is neighbour to this router.
	public boolean uniqueNeighbour(int[] router1){
		for(int i = 0; i < allNeighbours.size(); i++){
			if((allNeighbours.get(i)[0] == router1[0]) && (allNeighbours.get(i)[1] == router1[1])){
				return false;
			}
		}
		return true;
	}

	//Find all the neighbours by comparing links
	public void findNeighbours(){
		for(int i = 0; i < linkStateDatabase.size(); i++){
			for(int j = 0; j<linkStateDatabase.size(); j++){
				if((linkStateDatabase.get(i)[1] == linkStateDatabase.get(j)[1])&&
						(linkStateDatabase.get(i)[0] != linkStateDatabase.get(j)[0])){
					int[] entry = new int[4];
					entry[0] = linkStateDatabase.get(j)[0];
					entry[1] = linkStateDatabase.get(i)[0];
					entry[2] = linkStateDatabase.get(i)[1];
					entry[3] = linkStateDatabase.get(i)[2];
					if(uniqueNeighbour(entry)){
						allNeighbours.add(entry);
					}
					break;
				}
			}
			//}
	}


}		

//Dijkstra's Algorithm to be run with the information from our Link State Database
public void dijkstraAlgorithm(int sourceRouter) throws Exception {
	try{
		dist[sourceRouter-1] = 0;
		for(int i = 0; i < nbrRouter; i++){	
			if((i+1) != sourceRouter){
				dist[i] = Integer.MAX_VALUE-100-i;
				previous[i] = -1;
			}
			int[] queueEntry = new int[2];		
			queueEntry[0] = i+1;
			queueEntry[1] = dist[i];
			queue.add(queueEntry);		
		}

		while(queue.peek() != null){
			int[] u = new int[2];
			System.arraycopy(queue.poll(), 0, u, 0, u.length); 
			for(int j = 0; j < allNeighbours.size(); j++){
				if(allNeighbours.get(j)[0] == u[0]){
					int alt = dist[u[0]-1] + allNeighbours.get(j)[3];
					if(alt < dist[allNeighbours.get(j)[1] - 1]){

						dist[allNeighbours.get(j)[1] - 1] = alt;
						previous[allNeighbours.get(j)[1] - 1] = u[0];
						changePriority(allNeighbours.get(j)[1] - 1, alt);
					}
				} 
			}

		}

	}catch(Exception e){
		System.err.println("dijkstra algorithm failed");
		System.exit(1);
	}
}

//Find the next hop given we know the shortest paths (we just ran Dijkstra Algorithm so we should have shortest paths)
public void findNextHops() throws Exception {
	try{
		int newprev;
		int oldprev;
		for(int i = 0; i < previous.length; i++){
			if(previous[i] == routerID){
				nextHops[i] = i+1;
			}
			else if(previous[i] == 0){
				nextHops[i] = 0;
			}
			else if(previous[i] == -1){
				nextHops[i] = -1;
			}
			else{
				newprev = previous[i];
				oldprev = previous[i]; //2
				while(newprev != routerID){
					newprev = previous[oldprev - 1]; // 1

					if(newprev == routerID){
						break;
					}

					oldprev = newprev; // 2 == 1
				}
				nextHops[i] = oldprev;
			}
		}

	} catch (Exception e){
		System.err.println("failed to find next hops");
		System.exit(1);
	}
}

//Print the distance vector used in Dijkstra's Algorithm (testing purposes).
public void printDist() throws Exception {
	for(int i = 0; i < dist.length; i++){
		System.out.println(previous[i] + " " + dist[i]);
	}		
}

public void changePriority(int routerID, int priority){
	try{
	while(queue.peek() != null){
		int[] temp = new int[2];
		temp[0] = queue.peek()[0];
		temp[1] = queue.peek()[1];
		tempQueue.add(temp);
		queue.poll();
	}
	for(int j = 0; j < tempQueue.size(); j++){
		int[] temp2 = new int[2];
                temp2[0] = tempQueue.get(j)[0];
                temp2[1] = tempQueue.get(j)[1];
		queue.add(temp2);
	}
	}catch (Exception e){
		System.err.println("changePriority failed");
		System.exit(1);
	}
	
}


//Write the Routing Information Base to log file.
public void printRIB() throws Exception {
	String output = "";
	try{
		output = output + "# RIB\n";
		for(int i = 0; i < nbrRouter; i++){
			output = output + "R" + routerID + " -> ";
			output = output + "R" + (i+1) + " -> ";
			if(nextHops[i] == -1){
				output = output + "INF";
			}
			else if(nextHops[i] == 0){
				output = output + "Local";
			}
			else{
				output = output + "R" + nextHops[i];
			}
			output = output + "," + dist[i] + "\n";
		}
		output = output + "\n";
		System.out.println("Writing to log file");
		routerWriter.write(output);
		routerWriter.flush();
	} catch (Exception e){
		System.err.println("print RIB");
		System.exit(1);
	}
}


public static void main(String [ ] args) throws Exception {

	if( args.length != 4) {
		System.err.println("Usage: router <router_id> <nse_host> <nse_port> <router_port>\n\t\t<router_id> is an integer that represents the router id. It should be unique for each router. \n" +
				"\t\t<nse_host> is the host where the Network State Emulator is running. \n" +
				"\t\t<nse_port> is the port number of the Network State Emulator. \n" +
				"\t\t<router_port> is the router port \n");
		System.exit(1);
	}
	router r = new router(args[0],args[1],args[2],args[3]);
	System.out.println("Creating log file");
	r.routerWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("router" + args[0] + ".log"), "utf-8"));
	try{
		System.out.println("Sending out Initial Packets...");
		r.sendInit();
		System.out.println("Receiving Circuit Database");
		r.receiveCDB();
		r.parseCDB();
		System.out.println("Sending Hello Packets");
		r.sendHelloPackets();
		System.out.println("Sending out Link State Database Packets");
		r.sendLinkStatePackets();
	}catch(Exception e) {
		System.err.println("Whoops! Something unexepceted happened. Please try again! :)");
	}

}
}


