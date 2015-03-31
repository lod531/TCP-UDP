import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Timer;
//import java.net.DatagramSocket;

import java.util.TimerTask;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.InetAddress;
import java.util.ArrayList;

import tcdIO.Terminal;

public class Server extends Node 
{
	static final int DEFAULT_PORT = 50001;

	Terminal terminal;
	
	//MY ADDITIONS:
	
	private final int INITIALIZER_FRAME_LENGTH = 1;
	private final int DATA_FRAME_LENGTH = 2;
	private final int TERMINATOR_FRAME_LENGTH = 4;	//all fuckin X's, in terms of contents.
	
	private final int INITIALIZER__FRAME_DATA_INDEX = 0;
	
	private final int DATA_FRAME_DATA_INDEX = 0;
	private final int DATA_FRAME_FRAME_NUMBER_INDEX = 1;
	
	private final int ACK_FRAME_LENGTH = 2;	//ackFrame[0] = data from previous frame (piggybacking), ackFrame[1] = nextExpectedFrameNumber
	private final int NAK_FRAME_LENGTH = 3;
	
	private final int ACK_FRAME_DATA_INDEX = 0;
	private final int ACK_FRAME_NEXT_FRAME_NUMBER_INDEX = 1;
	
	private final int FRAME_NUMBER_LIMIT = 16;
	private final int WINDOW_SIZE = FRAME_NUMBER_LIMIT/2;
	ArrayList<ServerCluster> servers;

	
	
	Server(Terminal terminal, int port) {
		try {
			this.terminal= terminal;
			socket= new DatagramSocket(port);
			listener.go();
			servers = new ArrayList<ServerCluster>();
			
		}
		catch(java.lang.Exception e) {e.printStackTrace();}
	}
	
	public synchronized void start() throws Exception {
		terminal.println("Waiting for contact");
		this.wait();
	}
	
	private class ServerCluster
	{
		private final byte[] IP;
		private ArrayList<ServerThread> array;
		
		ServerCluster(byte[] passedAddress)
		{
			IP = passedAddress;
			array = new ArrayList<ServerThread>();
		}
		private void addServerThread(ServerThread passedServerThread)
		{
			array.add(passedServerThread);
		}
		
		private void removeServerThread(ServerThread passedServerThread)
		{
			array.remove(passedServerThread);
		}
		
		public int size() 
		{
			return array.size();
		}
	}

	private boolean compareIPs(byte[] src1, byte[] src2)
	{
		boolean result = false;
		for(int index = 0; index < src1.length && !result; index++)
		{
			result = src1[index] == src2[index];
		}
		return result;
	}
	
	public void onReceipt(DatagramPacket packet) {
		try 
		{
			byte[] receivedIP = packet.getAddress().getAddress();
			boolean newIPAddress = true;
			ListIterator<ServerCluster> serverIterator = servers.listIterator();
			ServerCluster targetCluster = null;
			while(serverIterator.hasNext() && newIPAddress)
			{
				targetCluster = serverIterator.next();
				boolean temp = compareIPs(receivedIP, targetCluster.IP);
				newIPAddress = !temp;
			}
			if(newIPAddress && !serverIterator.hasNext())
			{
				targetCluster = new ServerCluster(receivedIP);
				servers.add(targetCluster);
			}
			//AT THIS POINT TARGETCLUSTER HAS BEEN FOUND
			//If it wasn't there, as in it was a new IP address, I would have created a new
			//cluster to accommodate for all port sub-connections associated with it
			
			int receivedPort = packet.getPort();
			boolean newPort = true;
			ListIterator<ServerThread> threadIterator = targetCluster.array.listIterator();
			ServerThread targetThread = null;
			while(threadIterator.hasNext() && newPort)
			{
				targetThread = threadIterator.next();
				boolean temp = targetThread.associatedPort == receivedPort;
				newPort = !temp;
			}
			if(newPort && !threadIterator.hasNext())
			{
				targetThread = new ServerThread(receivedPort);
				targetCluster.addServerThread(targetThread);
			}
			
			
			//targetThread has been found and the received packet will now be passed to it.
			targetThread.onReceipt(packet);
		}
		catch(Exception e) {e.printStackTrace();}
	}
	
	private class ServerThread
	{
		private byte[] data;
		private int dataIndex;	//DATA INDEX IS EMPTY ASCENDING.
		
		private ServerThreadTimeOut terminatorTimeout;
		private Timer timer;
		
		private boolean[] receivedFrames;
		
		private final long TIMEOUT_DELAY = 50;
		
		private boolean transmissionInProgress;
		
		private int associatedPort;
		
		//private byte[] IP;
		
		ServerThread(int passedPort)
		{
			//MY ADDITIONS:
			data = null;
			dataIndex = 0;
			transmissionInProgress = false;
			timer = new Timer();
			terminatorTimeout = null;
			receivedFrames = new boolean[FRAME_NUMBER_LIMIT];
			associatedPort = passedPort;
			//IP = passedIP;
		}
		
		/*
		 * ALL OF THE CODE BELOW IS A DIFFERENT VERSION OF THE IMPLEMENTATION
		 * However, the implementation currently being used contains zero bugs, as opposed to the one below
		 * Which sometimes gets a little screwed.
		private void onInitializerReceipt(DatagramPacket packet) throws Exception
		{
			if(transmissionInProgress && packet.getData()[INITIALIZER__FRAME_DATA_INDEX] != data.length)
			{
				terminal.println("Received new initializer of length: " + packet.getData()[INITIALIZER__FRAME_DATA_INDEX]
						+ "\nReset all fields for new data."
						+ "\nOld data discarded.\n");
				resetAndInitialize(packet);
				sendInitializerACK(packet);
			}
			else if(!transmissionInProgress)
			{
				terminal.println("Received new initializer of length: " + packet.getData()[INITIALIZER__FRAME_DATA_INDEX]);
				resetAndInitialize(packet);
				sendInitializerACK(packet);
			}
			else
			{
				terminal.println("Received already acknowledged initializer.\nInitializer discarded, ACK resent");
				sendInitializerACK(packet);
			}
		}

		private void onDataReceipt(DatagramPacket packet) throws Exception
		{
			int receivedFrameNumber = packet.getData()[DATA_FRAME_FRAME_NUMBER_INDEX];
			int currentFrameNumber = dataIndex%FRAME_NUMBER_LIMIT;
			if(dataIndex == data.length)
			{
				DatagramPacket terminator = sendTerminator(packet);
				setTerminatorTimeout(terminator);
				transmissionInProgress = false;
				displayResult();
			}
			else if(isValidFrame(currentFrameNumber, receivedFrameNumber))
			{
				terminal.println("Received frame numbered: " + receivedFrameNumber + "\n");
				if(receivedFrameNumber == currentFrameNumber)
				{
					data[dataIndex] = packet.getData()[DATA_FRAME_DATA_INDEX];
					dataIndex++;
					
					for(int index = currentFrameNumber; index%FRAME_NUMBER_LIMIT != (currentFrameNumber + WINDOW_SIZE)%FRAME_NUMBER_LIMIT
							&& receivedFrames[index%FRAME_NUMBER_LIMIT]; index++)
					{
						dataIndex++;
						receivedFrames[index%FRAME_NUMBER_LIMIT] = false;
					}
					int nextExpectedFrameNumber = (dataIndex)%FRAME_NUMBER_LIMIT;
					sendACK(packet, nextExpectedFrameNumber);
				}
				else if(isValidFrame(currentFrameNumber, receivedFrameNumber))	//823456789123456789
				{
					//splitting the two conditions
					if(receivedFrameNumber > currentFrameNumber) // && receivedFrameNumber < currentFrameNumber + WINDOW_SIZE)
					{
						if(!receivedFrames[receivedFrameNumber] && dataIndex + receivedFrameNumber - currentFrameNumber < data.length
								&& dataIndex + receivedFrameNumber - currentFrameNumber > dataIndex && data[dataIndex + receivedFrameNumber - currentFrameNumber] == 0)
						{
							data[dataIndex + receivedFrameNumber - currentFrameNumber] = packet.getData()[DATA_FRAME_DATA_INDEX];
							receivedFrames[receivedFrameNumber] = true;
						}
					}
					else //if(receivedFrameNumber < currentFrameNumber && receivedFrameNumber < (currentFrameNumber+WINDOW_SIZE)%FRAME_NUMBER_LIMIT))
					{
						if(!receivedFrames[receivedFrameNumber] && dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber < data.length 
								&& dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber > dataIndex && data[dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber] == 0)
						{
							data[dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber] = packet.getData()[DATA_FRAME_DATA_INDEX];
							receivedFrames[receivedFrameNumber] = true;
						}
					}
					sendNAK(packet, currentFrameNumber);
				}
				if(dataIndex == data.length)
				{
					DatagramPacket terminator = sendTerminator(packet);
					setTerminatorTimeout(terminator);
					transmissionInProgress = false;
					displayResult();
				}
			}
			else
			{
				sendACK(packet, currentFrameNumber);//if this line is reached, the frame received was out of window range.
			}
		}

		private void onTerminatorReceipt()
		{
			if(terminatorTimeout != null)
			{
				terminatorTimeout.cancel();
				terminatorTimeout = null;
			}
		}
		
		*/
		
		public void onReceipt(DatagramPacket packet) {
			try 
			{
				switch(packet.getLength())
				{
					case(INITIALIZER_FRAME_LENGTH):
					{
						if(transmissionInProgress && packet.getData()[INITIALIZER__FRAME_DATA_INDEX] != data.length)
						{
							terminal.println("Received new initializer of length: " + packet.getData()[INITIALIZER__FRAME_DATA_INDEX]
									+ "\nReset all fields for new data."
									+ "\nOld data discarded.\n");
							resetAndInitialize(packet);
							sendInitializerACK(packet);
						}
						else if(!transmissionInProgress)
						{
							terminal.println("Received new initializer of length: " + packet.getData()[INITIALIZER__FRAME_DATA_INDEX]);
							resetAndInitialize(packet);
							sendInitializerACK(packet);
						}
						else
						{
							terminal.println("Received already acknowledged initializer.\nInitializer discarded, ACK resent");
							sendInitializerACK(packet);
						}
						break;
					}
					
					case(DATA_FRAME_LENGTH):
					{
						int currentFrameNumber = dataIndex%FRAME_NUMBER_LIMIT;
						int receivedFrameNumber = packet.getData()[DATA_FRAME_FRAME_NUMBER_INDEX];
						if(dataIndex == data.length)
						{
							sendTerminator(packet);
						}
						else if(isValidFrame(currentFrameNumber, receivedFrameNumber))
						{
							terminal.println("Received frame numbered: " + receivedFrameNumber + "\n");
							if(receivedFrameNumber == currentFrameNumber)
							{
								data[dataIndex] = packet.getData()[DATA_FRAME_DATA_INDEX];
								dataIndex++;
								currentFrameNumber = dataIndex%FRAME_NUMBER_LIMIT;
								for(int index = currentFrameNumber; index%FRAME_NUMBER_LIMIT != (currentFrameNumber + WINDOW_SIZE)%FRAME_NUMBER_LIMIT
										&& receivedFrames[index%FRAME_NUMBER_LIMIT]; index++)
								{
									dataIndex++;
									receivedFrames[index%FRAME_NUMBER_LIMIT] = false;
								}
								int nextExpectedFrameNumber = (dataIndex)%FRAME_NUMBER_LIMIT;
								sendACK(packet, nextExpectedFrameNumber);
							}
							
							else if(isValidFrame(currentFrameNumber, receivedFrameNumber))
							{
								//splitting the two conditions
								if(receivedFrameNumber > currentFrameNumber) // && receivedFrameNumber < currentFrameNumber + WINDOW_SIZE)
								{
									if(!receivedFrames[receivedFrameNumber] && (dataIndex + receivedFrameNumber - currentFrameNumber < data.length)
											&& (dataIndex + receivedFrameNumber - currentFrameNumber > dataIndex))
									{
										data[dataIndex + receivedFrameNumber - currentFrameNumber] = packet.getData()[DATA_FRAME_DATA_INDEX];
										receivedFrames[receivedFrameNumber] = true;
									}
								}
								else //if(receivedFrameNumber < currentFrameNumber && receivedFrameNumber < (currentFrameNumber+WINDOW_SIZE)%FRAME_NUMBER_LIMIT))
								{
									if(!receivedFrames[receivedFrameNumber] && dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber < data.length 
											&& (dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber > dataIndex))
									{
										data[dataIndex + FRAME_NUMBER_LIMIT - currentFrameNumber + receivedFrameNumber] = packet.getData()[DATA_FRAME_DATA_INDEX];
										receivedFrames[receivedFrameNumber] = true;
									}
								}
								sendNAK(packet, currentFrameNumber);
							}
							
							else
							{
								sendACK(packet, currentFrameNumber);//if this line is reached, the frame received was out of window range.
							}
							if(dataIndex == data.length)
							{
								DatagramPacket terminator = sendTerminator(packet);
								setTerminatorTimeout(terminator);
								transmissionInProgress = false;
								displayResult();
							}
						}
						else
						{
							sendACK(packet, currentFrameNumber);
						}
						break;
					}
					case(TERMINATOR_FRAME_LENGTH):
					{
						if(terminatorTimeout != null)
						{
							terminatorTimeout.cancel();
							terminatorTimeout = null;
						}
						break;
					}
				}
			}
			catch(Exception e) {e.printStackTrace();}
		}
		private void resetAndInitialize(DatagramPacket packet)
		{
			data = new byte[packet.getData()[INITIALIZER__FRAME_DATA_INDEX]];
			dataIndex = 0;
			transmissionInProgress = true;
			if(terminatorTimeout != null)
			{
				terminatorTimeout.cancel();
				terminatorTimeout = null;
			}
		}
		
		private void sendInitializerACK(DatagramPacket packet) throws Exception
		{
			byte[] ACKData = {(byte) data.length};
			assert(ACKData.length == INITIALIZER_FRAME_LENGTH);
			DatagramPacket ACK = new DatagramPacket(ACKData, INITIALIZER_FRAME_LENGTH, packet.getSocketAddress());
			socket.send(ACK);
		}
		
		public DatagramPacket sendTerminator(DatagramPacket packet) throws Exception		//public because ServerTimeOut needs access. I'd define it as a friend, but there is no such thing in java
		{
			byte[] terminatorData = {'X', 'X', 'X', 'X'};
			assert(terminatorData.length == TERMINATOR_FRAME_LENGTH);
			DatagramPacket terminator = new DatagramPacket(terminatorData, TERMINATOR_FRAME_LENGTH, packet.getSocketAddress());
			socket.send(terminator);
			//terminal.println("Terminator sent.\n");
			return terminator;
		}
		
		
		private void setTerminatorTimeout(DatagramPacket terminator)
		{
			if(terminatorTimeout == null)
			{
				terminatorTimeout = new ServerThreadTimeOut(this, terminator);
				timer.scheduleAtFixedRate(terminatorTimeout, TIMEOUT_DELAY, TIMEOUT_DELAY);
			}
		}
		
		
		private boolean isValidFrame(int currentFrameNumber, int receivedFrameNumber)
		{
			return (((receivedFrameNumber > currentFrameNumber && receivedFrameNumber < currentFrameNumber + WINDOW_SIZE) 
					|| (receivedFrameNumber < currentFrameNumber && receivedFrameNumber < (currentFrameNumber + WINDOW_SIZE)%FRAME_NUMBER_LIMIT && currentFrameNumber > FRAME_NUMBER_LIMIT/2) 
						|| (currentFrameNumber == receivedFrameNumber)));
			//this kind of huge condition filters out all invalid frames, were any to appear. All frames accepted are within window.
		}
		
		private void sendACK(DatagramPacket packet, int nextExpectedFrameNumber) throws Exception
		{
			byte[] ACKData = {packet.getData()[DATA_FRAME_DATA_INDEX], (byte) nextExpectedFrameNumber};
			assert(ACKData.length == ACK_FRAME_LENGTH);
			DatagramPacket ACK = new DatagramPacket(ACKData, ACK_FRAME_LENGTH, packet.getSocketAddress());
			terminal.println("Sent ACK with expected frame number: " + nextExpectedFrameNumber + "\n");
			socket.send(ACK);
		}
		
		private void sendNAK(DatagramPacket packet, int nextExpectedFrameNumber) throws Exception
		{
			byte[] NAKData = {'X', 'X', (byte) nextExpectedFrameNumber};
			assert(NAKData.length == NAK_FRAME_LENGTH);
			DatagramPacket NAK = new DatagramPacket(NAKData, NAK_FRAME_LENGTH, packet.getSocketAddress());
			terminal.println("Sent NAK with expected frame number: " + nextExpectedFrameNumber + "\n");
			socket.send(NAK);
		}
		
		private void displayResult()
		{
			String result = "";
			for(int index = 0; index < data.length; index++)
			{
				result += (char) data[index];
			}
			terminal.println("Resulting string: " + result + "\n");
		}
	}
	
	
	
	

	private class ServerThreadTimeOut extends TimerTask
	{
		private final ServerThread associatedServerThread;
		private final DatagramPacket associatedPacket;
		public ServerThreadTimeOut(ServerThread passedServerThread, DatagramPacket passedPacket)
		{
			associatedServerThread = passedServerThread;
			associatedPacket = passedPacket;
		}
		
		public void run() 
		{
			try {
				associatedServerThread.sendTerminator(associatedPacket);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	public static void main(String[] args) {
		try {					
			Terminal terminal= new Terminal("Server");
			(new Server(terminal, DEFAULT_PORT)).start();
			terminal.println("Program completed");
		} catch(java.lang.Exception e) {e.printStackTrace();}
	}
}
