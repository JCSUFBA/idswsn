package projects.ids_wsn.nodeDefinitions.routing.fuzzy;

import java.awt.Color;
import java.util.Enumeration;
import java.util.Hashtable;

import projects.ids_wsn.Utils;
import projects.ids_wsn.nodeDefinitions.BasicNode;
import projects.ids_wsn.nodeDefinitions.routing.IRouting;
import projects.ids_wsn.nodes.messages.FloodFindDsdv;
import projects.ids_wsn.nodes.messages.PayloadMsg;
import projects.ids_wsn.nodes.timers.SimpleMessageTimer;
import sinalgo.nodes.Node;
import sinalgo.nodes.messages.Message;
import sinalgo.tools.Tools;
import sinalgo.tools.logging.Logging;

public class FuzzyRouting implements IRouting {
	
	//Number of entries in Routing Table
	private Integer numBuffer = 3; 
	
	public int seqID = 0;
	
	//Fuzzy Routing Table
	protected Hashtable<Node, FuzzyRoutingEntry> fuzzyRoutingTable = new Hashtable<Node, FuzzyRoutingEntry>();
	
	private BasicNode node;

	public Node getBestRoute(Node destino) {
		FuzzyRoutingEntry fRout = fuzzyRoutingTable.get(destino);
		return fRout.getFirstActiveRoute();
	}

	public Boolean isNodeNextHop(Node destination) {
		return null;
	}

	public void receiveMessage(Message message) {
		if (message instanceof FloodFindDsdv){
			receiveFloodFindMessage(message);
		}else if (message instanceof PayloadMsg){
			PayloadMsg payloadMessage = (PayloadMsg) message;
			receivePayloadMessage(payloadMessage);			
		}else{
			
		}

	}
	
	private void controlColor(){
		node.setColor(Color.YELLOW);
		Utils.restoreColorNodeTimer(node, 5);
	}
	
	private void receivePayloadMessage(PayloadMsg payloadMessage) {
		FuzzyRoutingEntry fre = null;
		
		
		if (payloadMessage.nextHop == null ){ //It's a broadcast
			if (payloadMessage.ttl > 1) {
				Boolean forward = Boolean.TRUE;
				if (payloadMessage.imediateSender.equals(node)){ // The message bounced back
					forward = Boolean.FALSE;
				}else if (payloadMessage.immediateSource.equals(node)){ // The forwarding node is retransmiting a message that the node have just transmitted.
					forward = Boolean.FALSE;					
				}
				else{
					payloadMessage.ttl--;
					payloadMessage.immediateSource = payloadMessage.imediateSender;
					payloadMessage.imediateSender = node;
				}
				if (forward){
					controlColor();
					sendBroadcast(payloadMessage);
				}
			}
		}else if (payloadMessage.nextHop.equals(node)){
			
			controlColor();
			fre = fuzzyRoutingTable.get(payloadMessage.baseStation);
			payloadMessage.nextHop = fre.getFirstActiveRoute();
			payloadMessage.immediateSource = payloadMessage.imediateSender;
			
			if (payloadMessage.nextHop.equals(payloadMessage.immediateSource)){
				payloadMessage.nextHop = fre.getActiveRoute(1);
			}
			payloadMessage.imediateSender = node;
			
			sendMessage(payloadMessage);
		}
	}

	public void sendBroadcast(Message message) {
		sendMessage(message);
	}

	public void sendMessage(Integer value) {
		node.seqID++;
		Node destino = getSinkNode();
		Node nextHopToDestino = getBestRoute(destino);
		
		PayloadMsg msg = new PayloadMsg(destino, node, nextHopToDestino, node);
		msg.value = value;
		msg.immediateSource = node;
		msg.sequenceNumber = ++this.seqID;
		
		SimpleMessageTimer messageTimer = new SimpleMessageTimer(msg);
		messageTimer.startRelative(1, node);
	}
	
	public void sendMessage(Message message) {		
		SimpleMessageTimer messageTimer = new SimpleMessageTimer(message);
		messageTimer.startRelative(1, node);
	}

	public void setNode(BasicNode node) {
		this.node = node;

	}
	
	private void receiveFloodFindMessage(Message message){
		Logging myLog = Utils.getGeneralLog();
		
		FloodFindDsdv floodMsg = (FloodFindDsdv) message;
		Boolean forward = Boolean.TRUE;
		Float energy = 0f;
		Integer numHops = 0;
		Double fsl = 0d;
		
		if (floodMsg.forwardingNode.equals(node)){ // The message bounced back. The node must discard the msg.
			forward = Boolean.FALSE;
		}else if(floodMsg.immediateSource.equals(node)){ // The forwarding node is retransmiting a message that the node have just transmitted.
			forward = Boolean.FALSE;
		}else{
			energy = floodMsg.energy;
			numHops = floodMsg.hopsToBaseStation;
			
			//Calculating the FSL
			fsl = Utils.calculateFsl(energy, numHops);
			
			FuzzyRoutingEntry re = fuzzyRoutingTable.get(floodMsg.baseStation);
			
			if (re == null){
				fuzzyRoutingTable.put(floodMsg.baseStation, new FuzzyRoutingEntry(Integer.valueOf(floodMsg.sequenceID), Integer.valueOf(floodMsg.hopsToBaseStation), (Node)floodMsg.forwardingNode, Boolean.TRUE, fsl));
				
				myLog.logln("Rota;"+Tools.getGlobalTime()+";Rota Adicionada;"+node.ID+";"+floodMsg.baseStation+";"+floodMsg.forwardingNode.ID+";"+floodMsg.sequenceID+";"+floodMsg.hopsToBaseStation+";"+floodMsg.energy+";"+fsl);
				
			}else if (!re.containsNodeInNextHop(floodMsg.forwardingNode)){
				if (re.getFieldsSize() < numBuffer) { 
					re.addField(Integer.valueOf(floodMsg.sequenceID), Integer.valueOf(floodMsg.hopsToBaseStation), (BasicNode)floodMsg.forwardingNode, Boolean.TRUE, fsl);
					myLog.logln("Rota;"+Tools.getGlobalTime()+";Rota Adicionada;"+node.ID+";"+floodMsg.baseStation+";"+floodMsg.forwardingNode.ID+";"+floodMsg.sequenceID+";"+floodMsg.hopsToBaseStation+";"+floodMsg.energy+";"+fsl);
				}else{
					Boolean result = re.exchangeRoute(Integer.valueOf(floodMsg.sequenceID), Integer.valueOf(floodMsg.hopsToBaseStation), (BasicNode)floodMsg.forwardingNode, Boolean.TRUE, fsl);
					if (result) myLog.logln("Rota;"+Tools.getGlobalTime()+";Rota Trocada;"+node.ID+";"+floodMsg.baseStation+";"+floodMsg.forwardingNode.ID+";"+floodMsg.sequenceID+";"+floodMsg.hopsToBaseStation+";"+floodMsg.energy+";"+fsl); 
				}
				forward = Boolean.FALSE;
			}else {
				Boolean update = Boolean.FALSE;
				RoutingField field = re.getRoutingField(floodMsg.forwardingNode);
				if (field.getSequenceNumber() < floodMsg.sequenceID) { //Update an existing entrie
					update = Boolean.TRUE;
				}else{
					if (!floodMsg.baseStation.equals(floodMsg.source)){ // It's a beacon message (Source and Base Station values are not the same)
						update = Boolean.TRUE;
					}
				}
				
				if (update){
					field.setNumHops(floodMsg.hopsToBaseStation);
					field.setSequenceNumber(floodMsg.sequenceID);
					field.setNextHop((Node)floodMsg.forwardingNode);
					field.setFsl(fsl);
					myLog.logln("Rota;"+Tools.getGlobalTime()+";Rota Alterada;"+node.ID+";"+floodMsg.baseStation+";"+floodMsg.forwardingNode.ID+";"+floodMsg.sequenceID+";"+floodMsg.hopsToBaseStation+";"+floodMsg.energy+";"+fsl);
				}else{
					forward = Boolean.FALSE;
				}
			}
		}
		
		if (forward && floodMsg.ttl > 1){ //Forward the flooding message
			
			FloodFindDsdv copy = (FloodFindDsdv) floodMsg.clone();
			
			//We have to store the lowest energy found in the path
			if (node.getBateria().getEnergy().compareTo(copy.energy)<0){
				copy.energy = node.getBateria().getEnergy();				
			}
			
			copy.immediateSource = copy.forwardingNode;
			copy.forwardingNode = node;
			copy.ttl--;
			copy.hopsToBaseStation++;
			sendBroadcast(copy);
		}		
	}
	
	public void addRoute(){
		
	}
	
	public void exchangeRoute(){}

	/**
	 * Return the Sink node of the best route
	 */
	public Node getSinkNode() {
		
		Enumeration<Node> nodes = fuzzyRoutingTable.keys();
		Double fsl = null;
		Node sinkNode = null;
		
		while (nodes.hasMoreElements()){
			Node node = nodes.nextElement();
			FuzzyRoutingEntry fre = fuzzyRoutingTable.get(node);
			if (fsl == null){
				fsl = fre.getHighestFsl();
				sinkNode = node;
			}else{
				if (fre.getHighestFsl().compareTo(fsl) > 0){
					fsl = fre.getHighestFsl();
					sinkNode = node;
				}
			}
			
		}
		return sinkNode;
	}

	public void printRoutingTable() {
		Enumeration<Node> nodes = fuzzyRoutingTable.keys();
		
		
		while (nodes.hasMoreElements()){
			Tools.clearOutput();
			Node node = nodes.nextElement();
			FuzzyRoutingEntry fre = fuzzyRoutingTable.get(node);
			
			for (RoutingField field : fre.getRoutingFields()){
				Tools.appendToOutput("BS: "+node.ID+" / NextHop: "+field.getNextHop()+" / FSL: "+field.getFsl()+"\n");
			}
			
			
		}
		
	}

}