import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class UDPServer extends UDPHost{

	UDPServer(boolean _diagFlg, int _portServer) throws SocketException {
		this.diagnosticFlag = _diagFlg;
		this.udpPort  = _portServer;
		this.hostUDPSocket = new DatagramSocket(this.udpPort);
	}

	@Override
	public void begin() throws Exception {

		System.out.println("Server is alive and waiting for connections at "+ this.udpPort);
		
		//Keep the server alive
		while(true){
			
			//Wait for client connect
			DatagramPacket temp = new DatagramPacket(new byte[140], 140); 
			this.hostUDPSocket.receive(temp);
			
			//Create a thread to handle the client
			Thread objThread = new Thread(new ServiceClient(temp, this.diagnosticFlag, this.udpPort, this));
			objThread.start();
		}

	}

}









class ServiceClient implements Runnable{

	private static int count = 0;
	
	private boolean diagFlag;
	private int serverPort;
	private UDPServer objServer;
	
	private DatagramPacket clientPacket;
	private InetAddress clientIP;
	private int clientPort;
	
	private DatagramSocket serviceThread; //New Datagramsocket to service the client
	private DatagramPacket recPacket, sndPacket;
	
	private int numOfReTransmissions; //To hold the number of retransmissions
	private long timeOut;  //Default Time out
	
	private BufferedOutputStream bOS;
	private  Checksum chkSum;
	private MessageDigest fileHash;
	private byte[] hashValue;
	
	private TreeMap<Integer, byte[]> rcdPkts;
	
	public ServiceClient(DatagramPacket _packet, boolean _flag, 
			             int _serverPort, UDPServer _objServer) 
						 throws Exception{
		
		this.diagFlag = _flag;
		this.clientPacket = _packet;
		this.serverPort = _serverPort;
		this.objServer = _objServer;
		this.clientIP = _packet.getAddress();
		this.clientPort = _packet.getPort();
		
		++ServiceClient.count;
		this.serviceThread = new DatagramSocket(_serverPort+ServiceClient.count);
		this.numOfReTransmissions = 5;
		this.timeOut = 1000;
		
		this.bOS = new BufferedOutputStream(new FileOutputStream(new File("RecFile.txt")));
		this. chkSum= new CRC32();
		this.fileHash = MessageDigest.getInstance("SHA1");
		
		rcdPkts = new TreeMap<Integer, byte[]>();
		
	}
	
	
	
	
	
	@Override
	public void run() {
		
		System.out.println("Client at ip :" + this.clientIP + " and port "+
										  this.clientPort + " connected with server."
				                         );
		
		try{
			
			//Establish Connection
			if(establishConnection()){
				System.out.println("Client is alive");
				
				processMsg();

				acceptHashValue();
				
				closeConnection();
				
				System.out.println("Connection terminated.");

			}
			else
				throw new Exception("Connection could not be established with client");
			
		}
		catch(Exception e){
			objServer.printDiagnosticMsg("Exception raised while establishing connection");
		}
 
	}
	
	
	
	
	//Function to accept the hashValue from client
	private void acceptHashValue() throws IOException{
		
		int pyLdLen = ByteBuffer.wrap(this.recPacket.getData(), 36, 4).getInt();
		byte [] hashBytes = new byte[pyLdLen];
		
		StringBuffer strHashBuffFrmClient = new StringBuffer();
		for(int i =0; i< pyLdLen; ++i){
			hashBytes[i] = this.recPacket.getData()[i+40];
			strHashBuffFrmClient.append(Integer.toString((hashBytes[i] & 0xff) + 0x100, 16).substring(1));		
		
		}
		
		System.out.println("Hash received from the client: " + strHashBuffFrmClient.toString());
		
		StringBuffer strCalcHash = new StringBuffer();
		for(int i = 0; i< this.hashValue.length; ++i)
			strCalcHash.append(Integer.toString((this.hashValue[i] & 0xff) + 0x100, 16).substring(1));
		
		System.out.println("Hash value from file: " + strCalcHash.toString());
		
		//Check if both hash codes are equal or not
		String retStr = "fail";
		int ackN = 0;
		if(strCalcHash.toString().equalsIgnoreCase(strHashBuffFrmClient.toString())){
			retStr = "success";
			ackN = 1;
			System.out.println("Hash codes matched successfully");
		}
		packageMsg(this.objServer.buildMsg(40, 3, (long)0, (long)ackN, (long)0, 0, retStr.getBytes().length, retStr.getBytes()));
		this.serviceThread.send(this.sndPacket); // Send the response of the comparison
		
		//wait for response from client
		this.recPacket = new DatagramPacket(new byte[140], 140);
		
		while(true){
			try{
				this.serviceThread.receive(this.recPacket);
				break;
			}
			catch(SocketTimeoutException se){
				this.serviceThread.send(this.sndPacket); // ReSend the response of the comparison
			}
		}
		
		
	}
	
	
	
	
	//Function to close the connection by
	//acknowledging the close message
	private void closeConnection() throws IOException{
		
		this.packageMsg(this.objServer.buildMsg(40, 40, (long)0, (long)1, (long)0, 0, 0, null));
		this.serviceThread.send(this.sndPacket);

		//Repeat the for the number of times of retransmissions
		for(int i = 0; i< 3; ++i){
			
			try{
				this.serviceThread.setSoTimeout((int)this.timeOut); //Ensure the time out is not set to infinite
				this.serviceThread.receive(new DatagramPacket(new byte[140], 140));
				break;
			}
			catch(SocketTimeoutException e1){
				
				this.objServer.printDiagnosticMsg("Ack for Close msg Timed out");
				
				//Double the timeout till time out  is less than 64 secs
				if(this.timeOut <= 16000 )
					this.timeOut = 2*this.timeOut; 
				else{
					//set the time out by formula based on RTT
					//or increase it by 5 
					this.timeOut = 5+this.timeOut;
				}
					
					
				this.serviceThread.setSoTimeout((int)this.timeOut);
				
				//Resend the init message
				this.serviceThread.send(this.sndPacket);
				
				this.objServer.printDiagnosticMsg("Message resent");
				
			}
			
		}
		
		
	}

	
	
	
	private void processMsg() throws IOException{
		
		//expectedSeqNum = 0;
		
		//Set timeout to infinity
		//Receive msg
		//reset time out
		
		//extract type of msg
		//if typeOfMsg is 1 --> FT
		
			//if(seqNumofPacket == expectedSeqNum
				//Check  of the packet
				//if proper
					//send ack for seq num
					//write out to the file
				//
		
			//else
				//Check  of the packet
				//if proper
					//Add packet to list of received packets
		
		//else type
		
		
		//Accept data
		boolean firstPacketProcessed = false;
		
		this.recPacket = new DatagramPacket(new byte[1040], 1040);
		long currTimeOut = this.timeOut;
		this.serviceThread.setSoTimeout(0); //Set to infinite time out for the first packet
		
		this.serviceThread.receive(this.recPacket);	// Receive first packet
		
		this.serviceThread.setSoTimeout((int)currTimeOut);//Reset the timeout
		
		//check the msg type --> if file transfer then		
		int msgType = ByteBuffer.wrap(this.recPacket.getData(),4,4).getInt();
		long seqNumFrmClnt = ByteBuffer.wrap(this.recPacket.getData(),8,8).getLong();
		
		long ackNum = 0, expectedSeqNum = 0;
		
		
		
		//Msgtype 1 indicates filetransfer
		if(msgType == 1){
			
			
			System.out.println("Client is transferring a file.");
			
			
			//Repeat till msgType changes to 2 i.e end of file transfer
			while(true){
				
				if(msgType == 2)
					break;
				
				
				//Check if the current seq num matches the expected seq num
				//INSTEAD check for the existence of the seqNum in the TreeMap
//				if(seqNumFrmClnt == expectedSeqNum){
				if(! this.objServer.checkValInSet((int)seqNumFrmClnt, this.rcdPkts.keySet())){
					
					//Extract the 
					long chkSumFrmClnt = ByteBuffer.wrap(this.recPacket.getData(),24,8).getLong();
					
					//Extract the data
					byte[] data = this.recPacket.getData();
					int pyLdLen = ByteBuffer.wrap(data, 36, 4).getInt();
					
					//Extract the payload
					byte [] tempdata = new byte [pyLdLen];
					for(int i=0; i< pyLdLen; ++i)
						tempdata[i] = data[i + 40];
					
					//Check 
					if(checkData(tempdata, chkSumFrmClnt)){
						
						this.objServer.printDiagnosticMsg("Checksum Pass. Writing out packet. Seq Num:" + seqNumFrmClnt);
						
						//set the flag for first packet processed
						if(!firstPacketProcessed)
							firstPacketProcessed = true;
						
						
						//generate hash val
//						this.fileHash.update(tempdata);
						
						//Write out the bytes to the directory
//						this.bOS.write(tempdata);
//						this.bOS.flush();
						this.rcdPkts.put((int)seqNumFrmClnt, tempdata);
						
						//Current ack num
						ackNum = seqNumFrmClnt;
						
						//next expected sequence number is 
//						++expectedSeqNum;
						
						//build the acknowledgement
						packageMsg(this.objServer.buildMsg(40, 1, seqNumFrmClnt, ackNum, 0, 1, 0, null));
						
						//Send the acknowledgement
						this.serviceThread.send(this.sndPacket);
						
					}
					else
						//Print out the status
						this.objServer.printDiagnosticMsg("Checksum Fail for packet with Seq Num:" + seqNumFrmClnt);

					
					
				}
				else{ // Duplicate packet received
					
					//Send acknowledgement for the duplicate packet as the ack for the packet must have been lost
					packageMsg(this.objServer.buildMsg(40, 1, seqNumFrmClnt, seqNumFrmClnt, 0, 1, 0, null));
					this.serviceThread.send(this.sndPacket);
				}
				
				//Wait for next data packet
				waitForDatapacket(expectedSeqNum, firstPacketProcessed);
				
				//Extract the message type and the sequence number of the received message
				msgType = ByteBuffer.wrap(this.recPacket.getData(),4,4).getInt();
				seqNumFrmClnt = ByteBuffer.wrap(this.recPacket.getData(),8,8).getLong();
			}
			
			//For last part of file
			{
						this.objServer.printDiagnosticMsg("EOF Received");
						//Extract the 
						long chkSumFrmClnt = ByteBuffer.wrap(this.recPacket.getData(),24,8).getLong();
						
						//Extract the data
						byte[] dataLstPkt = this.recPacket.getData();
						int pyLdLenLstPkt = ByteBuffer.wrap(dataLstPkt, 36, 4).getInt();
						
						//Extract the payload
						byte [] tempdataLst = new byte [pyLdLenLstPkt];
						for(int i=0; i< pyLdLenLstPkt; ++i)
							tempdataLst[i] = dataLstPkt[i + 40];
						
						//Check 
						if(checkData(tempdataLst, chkSumFrmClnt)){
							
							this.objServer.printDiagnosticMsg("Checksum Pass. Writing out packet. Seq Num:" + seqNumFrmClnt);
							
							//set the flag for first packet processed
							if(!firstPacketProcessed)
								firstPacketProcessed = true;
							
							
							//generate hash val
//							this.fileHash.update(tempdataLst);
							
							//Write out the bytes to the directory
//							this.bOS.write(tempdataLst);
//							this.bOS.flush();
							
							this.rcdPkts.put((int)seqNumFrmClnt, tempdataLst);
							
							//Current ack num
							ackNum = seqNumFrmClnt;
							
							//next expected sequence number is 
							++expectedSeqNum;
							
							//build the acknowledgement
							packageMsg(this.objServer.buildMsg(40, 1, seqNumFrmClnt, ackNum, 0, 1, 0, null));
							
							//Send the acknowledgement
							this.serviceThread.send(this.sndPacket);
							
							waitForDatapacket(seqNumFrmClnt, firstPacketProcessed);	//In return - it'll contain the next control message
							
						}
						else
							//Print out the status
							this.objServer.printDiagnosticMsg("Checksum Fail for packet with Seq Num:" + seqNumFrmClnt);
						

			}
			
			
		}

		//Check if all packets have been received
		for(int seqNum : this.rcdPkts.keySet()){
			byte [] fileData =this.rcdPkts.get(seqNum); 
			this.bOS.write(fileData);
			this.fileHash.update(fileData);
		}
		
		
		this.bOS.flush();	//Flush the file output buffer
		this.bOS.close();	//Close the file output buffer
		this.hashValue = this.fileHash.digest();	//Complete the hash calculation
		
		
		
		System.out.println("File written.");
	}
	
	
	
	
	
	
	//Function to check the data using 
	private boolean checkData(byte[] data, long chkSumFrmClient){
		
		boolean retVal = false;
		
		this.chkSum = new CRC32();
		
		//generate the  for the data
		this.chkSum.update(data, 0, data.length);
		long genChkVal = this.chkSum.getValue();
		
		if(chkSumFrmClient == genChkVal)
			retVal = true;

		return retVal;
	}
	
	
	
	
	
	
	//Wait till client sends the correct packet
	private void waitForDatapacket(long expSeqNum, boolean firstPkt) throws IOException{
		
		this.recPacket = new DatagramPacket(new byte[1040], 1040);
		
//		//if the even the first packet has not been received correctly 
//		if(!firstPkt){
//			
//			//wait indefinitely
//			//Set time out to 0
//			
			this.serviceThread.setSoTimeout(0);
//		}
//		
//		while(true){
//			try{
//				
				this.serviceThread.receive(this.recPacket);
//				this.serviceThread.setSoTimeout((int)this.timeOut);
//				break;
//			}
//			catch(SocketTimeoutException e){
//				
//				this.objServer.printDiagnosticMsg("Timed out while waiting for datapacket with seq Num: " +expSeqNum);
//				
//				//Resend the acknowledgement
//				this.serviceThread.send(this.sndPacket);
//			}
			
			
//		}		
		
	}
	
	
	
	
	
	

	
	
	//Function used in 3 way handshake to establish connection
	private boolean establishConnection() throws Exception{
		
		boolean retVal = false;

		//Process the input
		//Extract the message type
		byte[] msgFromClient = this.clientPacket.getData();
		int m = ByteBuffer.wrap(msgFromClient, 4, 4).getInt();
		
		//Check for init message type
		if(0 == m){
			
			//Send the acknowledgement for init
			this.packageMsg(objServer.buildMsg(40, 0, (long)1, (long)1, (long)0, 0, 0, null));
			
			this.serviceThread.send(this.sndPacket);
			
			//wait for the client to send ack for the ack
			if(waitForRetransmission()){
				retVal = true;
			}
			
		}
		
		return retVal;
	}
	

	
	
	
	//Wait and try n times for acknowledgement
	private boolean waitForRetransmission() throws IOException{
		
		boolean retVal = false;
		this.recPacket = new DatagramPacket(new byte[140], 140);
				
		//Repeat the for the number of times of retransmissions
		for(int i = 0; i< this.numOfReTransmissions; ++i){
			
			try{
				this.serviceThread.receive(this.recPacket);
				retVal = true;
				break;
			}
			catch(SocketTimeoutException e1){
				
				this.objServer.printDiagnosticMsg("Timed out");
				
				//Double the timeout
				this.timeOut = 2*this.timeOut; 
				this.serviceThread.setSoTimeout((int)this.timeOut);
				
				//Resend the init message
				this.serviceThread.send(this.sndPacket);
				
				this.objServer.printDiagnosticMsg(("Time out increased to :"+this.timeOut));
				this.objServer.printDiagnosticMsg("Message resent");
				
			}
			
		}

		return retVal;
		
	}
	
	
	
	
	//Function to build the connection messages
	private void packageMsg(byte [] msgBuff){
			this.sndPacket = new DatagramPacket(msgBuff, 
									 msgBuff.length, this.clientIP,
									 this.clientPort);
	}
	
	
}



