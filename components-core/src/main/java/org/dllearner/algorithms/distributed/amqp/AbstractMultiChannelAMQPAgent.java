package org.dllearner.algorithms.distributed.amqp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

import org.apache.qpid.AMQException;
import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.client.AMQQueue;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.client.AMQTopic;
import org.apache.qpid.url.URLSyntaxException;
import org.dllearner.algorithms.distributed.MultiChannelMessagingAgent;
import org.dllearner.algorithms.distributed.containers.MessageContainer;
import org.dllearner.core.AbstractCELA;
import org.dllearner.core.AbstractClassExpressionLearningProblem;
import org.dllearner.core.AbstractReasonerComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractMultiChannelAMQPAgent extends AbstractCELA
		implements MultiChannelMessagingAgent{

	private Logger logger = LoggerFactory.getLogger(AbstractMultiChannelAMQPAgent.class);
	private static String sessionClosedErrMsg = "Seems the session was closed. Retrying.";
	private static String sessionReEstablishedMsg = "Re-created session";

	// default AMPQ values
	private String user = "admin";
	private String password = "admin";
	private String host = "localhost";
	private String clientId = null;
	private String virtualHost = null;
	private int port = 5672;
	private List<String> queueIdentifiers;

	// misc
	protected int myID;
	private int receivedMessagesCount;
	private int sentMessagesCount;

	// connection, session, queue, consumers, ...
	private Connection connection;
	private Session session;
	private Map<String, AMQQueue> queues;
	private String termRoutingKey = "terminate";
	private Topic termTopic;
	private TopicSubscriber termMsgSubscriber;
	private MessageProducer termMsgPublisher;

	// AMQP settings
	private boolean transactional = false;
	private boolean isMaster = false;
	private long maxBlockingWaitMilisecs = 5000;


	public AbstractMultiChannelAMQPAgent(AbstractClassExpressionLearningProblem problem, AbstractReasonerComponent reasoner) {
		super(problem, reasoner);
		queueIdentifiers = new ArrayList<String>();
		queues = new HashMap<String, AMQQueue>();
	}

	public AbstractMultiChannelAMQPAgent() {
		queueIdentifiers = new ArrayList<String>();
		queues = new HashMap<String, AMQQueue>();
	}

	@Override
	public void initMessaging() throws URLSyntaxException, AMQException, JMSException {
		// just in case the messaging agent's ID is not set
		if (myID == 0) {
			myID = (new Random()).nextInt(100000);
		}

		AMQConfiguration url = new AMQConfiguration(user, password, clientId, virtualHost, host, port);
		connection = new AMQConnection(url.getURL());
		connection.start();
		session = connection.createSession(transactional, Session.AUTO_ACKNOWLEDGE);

		initQueues();

		termTopic = new AMQTopic((AMQConnection) connection, termRoutingKey);
		termMsgSubscriber = session.createDurableSubscriber(termTopic, Integer.toString(myID));
		termMsgPublisher = session.createProducer(termTopic);

		sentMessagesCount = 0;
		receivedMessagesCount = 0;
	}

	@Override
	public void finalizeMessaging() throws JMSException {
		// TODO: delete queues
		// TODO: unsubscribe from term topic
		session.close();
		connection.close();
	}

	@Override
	public void blockingSend(MessageContainer msgContainer, String queueID) {
		logSend(msgContainer.toString(), queueID, true);
		try {
			Message msg = ((org.apache.qpid.jms.Session) session).createObjectMessage(msgContainer);
			MessageProducer producer = session.createProducer(queues.get(queueID));
			producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
			producer.send(msg);
			producer.close();

		} catch (IllegalStateException e) {
			try {
				checkAndRestoreSession();
			} catch (URLSyntaxException | AMQException | JMSException e1) {
				e1.printStackTrace();
				return;
			}
			blockingSend(msgContainer, queueID);

		} catch (JMSException e) {
			e.printStackTrace();
		}
		sentMessagesCount++;
	}

	@Override
	public void nonBlockingSend(MessageContainer msgContainer, String queueID) {
		logSend(msgContainer.toString(), queueID, false);

		Message msg;
		try {
			msg = ((org.apache.qpid.jms.Session) session).createObjectMessage(msgContainer);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try {
				ObjectOutputStream oout = new ObjectOutputStream(bout);
				oout.writeObject(msgContainer);
				oout.flush();
				logger.info("######### MSG CONTAINER SIZE: " + bout.size());
				oout.close();
				bout.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

	//        CompletionListener completionListener = new DummyCompletionListener();
			/* FIXME: asynchronous sending is not implemented in the default message
			 * producers:
			 * Exception in thread "main" java.lang.AbstractMethodError: org.apache.qpid.client.BasicMessageProducer_0_10.send(Ljavax/jms/Message;Ljavax/jms/CompletionListener;)V
			 * at org.dllearner.algorithms.distributed.amqp.AbstractAMQPCELOEAgent.nonBlockingSend(AbstractAMQPCELOEAgent.java:118)
			 * at org.dllearner.algorithms.distributed.amqp.DistScoreCELOEAMQP.startMaster(DistScoreCELOEAMQP.java:646)
			 * at org.dllearner.algorithms.distributed.amqp.DistScoreCELOEAMQP.start(DistScoreCELOEAMQP.java:560)
			 * at org.dllearner.algorithms.distributed.amqp.DistScoreCELOEAMQP.main(DistScoreCELOEAMQP.java:1509)
			 *
			 * TODO: check if we really need this async stuff here; I guess async
			 * just means non-blocking delivery to the queue, not to the receiver
			 */
	//        sender.send(msg, completionListener);
			MessageProducer producer = session.createProducer(queues.get(queueID));
			producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
			producer.send(msg);
			producer.close();

		} catch (IllegalStateException e) {
			try {
				checkAndRestoreSession();
			} catch (URLSyntaxException | AMQException | JMSException e1) {
				e1.printStackTrace();
				return;
			}
			nonBlockingSend(msgContainer, queueID);

		} catch (JMSException e) {
			e.printStackTrace();
		}

		sentMessagesCount++;
	}

	@Override
	public MessageContainer blockingReceive(String queueID) {
		logReceive(queueID, true);
		// FIXME: find a better solution than using a timeout
		ObjectMessage msg;
		MessageContainer msgContainer;
		try {
			MessageConsumer recver = session.createConsumer(queues.get(queueID));
			msg = (ObjectMessage) recver.receive(maxBlockingWaitMilisecs);
			recver.close();
			if (msg == null) return null;

			msgContainer = (MessageContainer) msg.getObject();

		} catch (IllegalStateException e) {
			try {
				checkAndRestoreSession();
			} catch (URLSyntaxException | AMQException | JMSException e1) {
				e1.printStackTrace();
				return null;
			}
			return blockingReceive(queueID);

		} catch (JMSException e) {
			e.printStackTrace();
			return null;
		}

		receivedMessagesCount++;
		logReceived(msgContainer.toString(), queueID);

		return msgContainer;
	}

	@Override
	public MessageContainer nonBlockingReceive(String queueID) {
		logReceive(queueID, false);

		ObjectMessage msg;
		try {
			MessageConsumer recver = session.createConsumer(queues.get(queueID));
			// FIXME: seems nowait makes trouble the way I use it; replaced for now
//			msg = (ObjectMessage) recver.receiveNoWait();
			msg = (ObjectMessage) recver.receive(1000); // just wait one second

			if (msg == null) {
				recver.close();
				return null;

			} else {
				MessageContainer msgContainer = (MessageContainer) msg.getObject();
				recver.close();
				receivedMessagesCount++;

				logReceived(msgContainer.toString(), queueID);
				return msgContainer;
			}

		} catch (IllegalStateException e) {
			try {
				checkAndRestoreSession();
			} catch (URLSyntaxException | AMQException | JMSException e1) {
				e1.printStackTrace();
				return null;
			}
			return nonBlockingReceive(queueID);

		} catch (JMSException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public void terminateAgents() throws JMSException {
		termMsgPublisher.send(session.createBytesMessage());
	}

	@Override
	public boolean checkTerminateMsg() {
		int failCntr = 0;

		while (true) {
			Message msg;
			try {
				// FIXME
//				msg = termMsgSubscriber.receiveNoWait();
				msg = termMsgSubscriber.receive(1000);
			} catch (IllegalStateException e) {
				try {
					checkAndRestoreSession();
				} catch (URLSyntaxException | AMQException | JMSException e1) {
					e1.printStackTrace();

					// terminate if error cannot be fixed here
					return true;
				}
				failCntr++;
				if (failCntr > 100) {
					logger.error("Terminating due to error:");
					e.printStackTrace();
					return true;
				}
				else continue;

			} catch (JMSException e) {
				e.printStackTrace();
				continue;
			}

			if (msg != null) {
				return true;
			} else {
				return false;
			}
		}
	}

	@Override
	public void setAgentID(int id) {
		myID = id;
	}

	@Override
	public int getAgentID() {
		return myID;
	}

	@Override
	public boolean isMaster() {
		return isMaster;
	}

	@Override
	public int getReceivedMessagesCount() {
		return receivedMessagesCount;
	}

	@Override
	public int getSentMessagesCount() {
		return sentMessagesCount;
	}

	// <------------------------ non-interface methods ----------------------->
	public boolean updateAMQPSettings(String propertiesFilePath) {
		Properties props = new Properties();
		try {
			props.load(new FileReader(new File(propertiesFilePath)));
		} catch (IOException e) {
			return false;
		}

		user = props.getProperty("user", user);
		password = props.getProperty("password", password);
		host = props.getProperty("host", host);
		clientId = props.getProperty("clientId", clientId);
		virtualHost = props.getProperty("virtualHost", virtualHost);
		port = props.getProperty("port") == null ? port : Integer.parseInt(
				props.getProperty("port"));
		return true;
	}

	/**
	 * FIXME: Seems this case should never happen, but does. So the cause need
	 * to be found
	 * @throws AMQException
	 * @throws URLSyntaxException
	 * @throws JMSException
	 */
	private void checkAndRestoreSession() throws URLSyntaxException, AMQException, JMSException {
		logger.error(sessionClosedErrMsg);
		if (((AMQConnection) connection).isClosed()) {
			AMQConfiguration url = new AMQConfiguration(user, password, clientId, virtualHost, host, port);
			connection = new AMQConnection(url.getURL());
		}

		if (((AMQSession) session).isClosed()) {
			session = connection.createSession(transactional, Session.AUTO_ACKNOWLEDGE);
			logger.info(sessionReEstablishedMsg);
		}

	}

	public void addTopic(String topicIdentifier) {
		queueIdentifiers.add(topicIdentifier);
	}

	public boolean isTransactional() {
		return transactional;
	}

	public void setTransactional(boolean transactional) {
		this.transactional = transactional;
	}

	public void setMaster() {
		isMaster = true;
	}

	private void initQueues() throws JMSException {
		for (String qeueID : queueIdentifiers) {
			AMQQueue q = new AMQQueue("amq.direct", qeueID);
			queues.put(qeueID, q);
		}
	}

	@Override
	public String toString() {
		if (isMaster) return "Master (" + myID + ")";
		else return "Worker " + myID;
	}

	private void logSend(String what, String to, boolean blocking) {
		StringBuilder sb = new StringBuilder();

		sb.append(System.currentTimeMillis());
		if (blocking) sb.append("|<--| ");
		else sb.append("| < | ");

		sb.append(this + " sending " + what + " to " + to);

		logger.info(sb.toString());
	}

	private void logReceive(String from, boolean blocking) {
		StringBuilder sb = new StringBuilder();

		sb.append(System.currentTimeMillis());
		if (blocking) {
			sb.append("|-->| ");
			sb.append(this);
			sb.append(" wating for incoming messages from ");
			sb.append(from);
		}
		else {
			return;
//			sb.append("| > | ");
//			sb.append(this);
//			sb.append(" checking incoming messages from ");
//			sb.append(from);
		}

		logger.info(sb.toString());
	}

	private void logReceived(String what, String from) {
		StringBuilder sb = new StringBuilder();

		sb.append(System.currentTimeMillis());
		sb.append("|  >| ");
		sb.append(this);
		sb.append(" received ");
		sb.append(what);
		sb.append(" from ");
		sb.append(from);

		logger.info(sb.toString());
	}
}
