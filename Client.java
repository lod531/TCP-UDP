//import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import tcdIO.*;

/**
 *
 * Client class
 * 
 * An instance accepts user input 
 *
 */
public class Client extends Node {
	static final int DEFAULT_SRC_PORT_ONE = 50000;
	static final int DEFAULT_SRC_PORT_TWO = 50002;
	static final int DEFAULT_DST_PORT = 50001;
	static final String DEFAULT_DST_NODE = "localhost";	
	
	//MY ADDITIONS:
	
	private final int INITIALIZER_FRAME_LENGTH = 1;
	private final int DATA_FRAME_LENGTH = 2;
	private final int TERMINATOR_FRAME_LENGTH = 4;
	
	private final int INITIALIZER__FRAME_DATA_INDEX = 0;
	
	private final int DATA_FRAME_DATA_INDEX = 0;
	private final int DATA_FRAME_FRAME_NUMBER_INDEX = 1;
	
	private final int ACK_FRAME_LENGTH = 2;	//ackFrame[0] = data from previous frame (piggybacking), ackFrame[1] = nextExpectedFrameNumber
	private final int NAK_FRAME_LENGTH = 3;
	
	private final int ACK_FRAME_DATA_INDEX = 0;
	private final int ACK_FRAME_NEXT_FRAME_NUMBER_INDEX = 1;
	
	private final int NAK_FRAME_FRAME_NUMBER_INDEX = 2;
	
	
	private final int FRAME_NUMBER_LIMIT = 16;
	private final int WINDOW_SIZE = FRAME_NUMBER_LIMIT/2;
	
	private Timer timer;
	private ClientTimeOut startTimeout;
	private ClientTimeOut timeOuts[];
	private final int TIMEOUT_DELAY = 50; //in milliseconds
	
	private byte[] data;
	private int dataIndex;
	
	private ArrayList<DatagramPacket> currentlySentPackets;
	
	private boolean transmissionInProgress;
	
	Terminal terminal; 
	InetSocketAddress dstAddress;
	


	
	
	
	Client(Terminal terminal, String dstHost, int dstPort, int srcPort) {
		try {
			this.terminal= terminal;
			dstAddress= new InetSocketAddress(dstHost, dstPort);
			socket= new DatagramSocket(srcPort);
			listener.go();
			
			//MY ADDITIONS
			timer = new Timer();
			data = null;
			dataIndex = 0;
			startTimeout = null;
			transmissionInProgress = false;
			timeOuts = null;
			currentlySentPackets = new ArrayList<DatagramPacket>();
		}
		catch(java.lang.Exception e) {e.printStackTrace();}
	}


	
	
	
	
	public void start() throws Exception 
	{
		this.reset();
		DatagramPacket initializerFrame= null;
		data= (terminal.readString("String to send: ")).getBytes();
		if(data.length > 127)
		{
			terminal.println("Too large an input.\n");
			this.start();
		}
		terminal.println("Sending initializer...\n");
		byte[] intializerData = {(byte) data.length};
		assert(intializerData.length == INITIALIZER_FRAME_LENGTH);
		initializerFrame = new DatagramPacket(intializerData, INITIALIZER_FRAME_LENGTH, dstAddress);
		socket.send(initializerFrame);
		terminal.println("Initializer sent.\n");
		startTimeout = new ClientTimeOut(this, initializerFrame, 0);		//0 because this is the first frame, essentially
		timer.scheduleAtFixedRate(startTimeout, TIMEOUT_DELAY, TIMEOUT_DELAY);
	}
	
	private void send() throws Exception
	{
		int dataIndexOffset = 0;
		int numberOfFramesSent = 0;
		while((dataIndex + dataIndexOffset)%FRAME_NUMBER_LIMIT != (dataIndex + WINDOW_SIZE)%FRAME_NUMBER_LIMIT
				&& (dataIndex + dataIndexOffset) < data.length)
		{
			int currentFrameNumber = (dataIndex + dataIndexOffset)%FRAME_NUMBER_LIMIT;
			if(timeOuts[currentFrameNumber] == null)			//i.e. if the frame has not already been sent
			{
				byte[] dataFrameData = { data[dataIndex + dataIndexOffset], (byte) currentFrameNumber};
				assert(dataFrameData.length == DATA_FRAME_LENGTH);
				DatagramPacket dataFrame = new DatagramPacket(dataFrameData, DATA_FRAME_LENGTH, dstAddress);
				currentlySentPackets.add(dataFrame);		//enqueues this data frame to possible NAK response array
				socket.send(dataFrame);
				terminal.println("Sent data frame numbered: " + currentFrameNumber 
						+ "\nContaining data of index:" + (dataIndex + dataIndexOffset));
				ClientTimeOut newTimeOut = new ClientTimeOut(this, dataFrame, currentFrameNumber);
				timer.scheduleAtFixedRate(newTimeOut, TIMEOUT_DELAY, TIMEOUT_DELAY);
				timeOuts[currentFrameNumber] = newTimeOut;
				dataIndexOffset++;
				numberOfFramesSent++;
			}
			else
			{
				dataIndexOffset++;
			}
		}
		terminal.println("NUMBER OF FRAMES SENT: " + numberOfFramesSent + "\n\n\n");
	}
	
	
	
	
	public void onReceipt(DatagramPacket packet) throws Exception 
	{
		int receivedFrameNumber = packet.getData()[ACK_FRAME_NEXT_FRAME_NUMBER_INDEX];
		int currentFrameNumber = dataIndex%FRAME_NUMBER_LIMIT;
		switch(packet.getLength())
		{
			case(INITIALIZER_FRAME_LENGTH):
			{
				if(packet.getData()[INITIALIZER__FRAME_DATA_INDEX] == data.length && !transmissionInProgress)
				{
					transmissionInProgress = true;
					startTimeout.cancel();
					startTimeout = null;
					terminal.println("Successful initialization!\n");
					this.send();
				}
				else
				{
					terminal.println("Invalid initializer ACK received.\nPrevious initializer will be resent upon timeout.\n");
				}
				break;
			}
			case(ACK_FRAME_LENGTH):
			{
				if(isValidACK(currentFrameNumber, receivedFrameNumber))
				{
					//annulate scheduled timeOuts of frames already delivered
					for(int index = currentFrameNumber; index%FRAME_NUMBER_LIMIT != receivedFrameNumber; index++)
					{
						if(timeOuts[index%FRAME_NUMBER_LIMIT] != null)
						{
							timeOuts[index%FRAME_NUMBER_LIMIT].cancel();
							timeOuts[index%FRAME_NUMBER_LIMIT] = null;
						}
						this.dequeueLastPacketSent();	//Dequeues appropriate sent packets from possible NAK queue
					}
					//appropriately increment dataIndex
					if(currentFrameNumber < receivedFrameNumber)
					{
						dataIndex += receivedFrameNumber - currentFrameNumber;
					}
					else
					{
						dataIndex += FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber;
					}
					this.send();
					if(dataIndex >= data.length)
					{
						this.start();
					}
				}
				break;
			}
			case(NAK_FRAME_LENGTH):
			{
				boolean found = false;
				Iterator <DatagramPacket> temp = currentlySentPackets.iterator();
				DatagramPacket targetPacket = null;
				while(temp.hasNext() && !found)
				{
					targetPacket = temp.next();
					found =  !( receivedFrameNumber == (int) targetPacket.getData()[DATA_FRAME_FRAME_NUMBER_INDEX]);
				}
				if(found)
				{
					socket.send(targetPacket);
					terminal.println("NAK SENT\n\n\n");
				}
				break;
			}
			
			case(TERMINATOR_FRAME_LENGTH):
			{
				if(transmissionInProgress)
				{
					terminal.println("Received terminator.\n");
					this.cancelTimeOuts();
					this.start();
				}
				break;
			}
		}
	}
	
	private void dequeueLastPacketSent() 
	{
		Iterator <DatagramPacket> temp = currentlySentPackets.iterator();
		while(temp.hasNext())
		{
			temp.next();
		}
		temp.remove();
	}






	public void timeOut(DatagramPacket packet, int frameNumber) throws Exception
	{
		if(!transmissionInProgress && frameNumber == 0)	//if this condition, frame being resent is the initializer
		{
			terminal.println("Initializer resent.\n");
			socket.send(packet);
		}
		else
		{
			terminal.println("Frame number " + frameNumber + " resent.\n");
			socket.send(packet);
		}
			
	}
	

	
	private void cancelTimeOuts()
	{
		if(startTimeout != null)
		{
			startTimeout.cancel();
			startTimeout = null;
		}
		for(int index = 0; index < timeOuts.length; index++)
		{
			if(timeOuts[index] != null)
			{
				timeOuts[index].cancel();
				timeOuts[index] = null;
			}
		}
	}
	
	private boolean isValidACK(int currentFrameNumber, int receivedFrameNumber)	//THIS FUNCTION IS DIFFERENT TO THE SERVER ONE
	{
		return ((receivedFrameNumber > currentFrameNumber && receivedFrameNumber < currentFrameNumber + WINDOW_SIZE + 1)
				|| (receivedFrameNumber < currentFrameNumber && receivedFrameNumber < (currentFrameNumber+WINDOW_SIZE + 1)%FRAME_NUMBER_LIMIT) && currentFrameNumber >= FRAME_NUMBER_LIMIT/2);
		//this kind of huge condition filters out all invalid frames, were any to appear. All frames accepted are within window.
		//the two + 1 adjustments are there because it is possible for the server to receive all frames in reverse order (so that it will send
		//an ACK for currentFrame+WINDOW_SIZE+1 frame, which would be discarded if it weren't for the adjustment, and the system would enter a permanent loop.
		//This, however, is not a hotfix. As per specifications of selective repeat, the server could indeed receive all frames in reverse order and enter this loop.
	}

	private void reset()
	{
		data= null;
		dataIndex = 0;
		startTimeout = null;
		transmissionInProgress = false;
		timeOuts = new ClientTimeOut[FRAME_NUMBER_LIMIT];
		currentlySentPackets.clear();
	}
	
	private class ClientTimeOut extends TimerTask
	{
		private final Client associatedClient;
		private final DatagramPacket associatedPacket;
		private final int associatedFrameNumber;
		public ClientTimeOut(Client passedClient, DatagramPacket passedPacket, int passedFrameNumber)
		{
			associatedClient = passedClient;
			associatedPacket = passedPacket;
			associatedFrameNumber = passedFrameNumber;
		}
		
		public void run() 
		{
			try {
				associatedClient.timeOut(associatedPacket, associatedFrameNumber);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	public static void main(String[] args) {
		try 
		{					
			Terminal terminalOne= new Terminal("Client1");		
			(new Client(terminalOne, DEFAULT_DST_NODE, DEFAULT_DST_PORT, DEFAULT_SRC_PORT_ONE)).start();
			Terminal terminalTwo= new Terminal("Client2");	
			(new Client(terminalTwo, DEFAULT_DST_NODE, DEFAULT_DST_PORT, DEFAULT_SRC_PORT_TWO)).start();
		} 
		catch(java.lang.Exception e) {e.printStackTrace();}
	}
}
