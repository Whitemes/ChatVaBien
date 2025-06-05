package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.GetUsersRequest;
import fr.upem.net.chatvabien.protocol.KOPrivateResquest;
import fr.upem.net.chatvabien.protocol.LoginRequest;
import fr.upem.net.chatvabien.protocol.Message;
import fr.upem.net.chatvabien.protocol.MessageRequest;
import fr.upem.net.chatvabien.protocol.OKPrivateRequest;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.Request;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.MessageReader;

public class ChatVaBienClient {

	static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private boolean closed = false;
        private enum State {WAITING_OPCODE, WAITING_PEUSDO, WAITING_MESSAGE, DONE, ERROR};
        private State state = State.WAITING_OPCODE;
        private byte opcode;

        private final MessageReader messageReader = new MessageReader();
        private final ByteReader byteReader = new ByteReader();
        
        private Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        /**
         * Process the content of bufferIn
         *
         * The convention is that bufferIn is in write-mode before the call to process
         * and after the call
         *
         */
        private void processIn() {
        	for (; ; ) {
        		switch(state) {
        		case WAITING_OPCODE -> {
                    var status = byteReader.process(bufferIn);
                    if (status == ProcessStatus.DONE) {
                        opcode = byteReader.get();
                        byteReader.reset();
                        state = State.WAITING_MESSAGE;
                    } else if (status == ProcessStatus.REFILL) {
                        return;
                    } else {
                        state = State.ERROR;
                    }
                }
	        		case WAITING_MESSAGE -> {
	        			var status = messageReader.process(bufferIn);
	        		    if (status == ProcessStatus.DONE) {
	        		        Message receivedMessage = messageReader.get();
	        		        System.out.println("Received: " + receivedMessage);
	        		        messageReader.reset();
	        		        state = State.DONE;
	        		    } else if (status == ProcessStatus.REFILL) {
	        		        return;
	        		    } else {
	        		        closed = true;
	        		        return;
	        		    }
	        		}
	        		case DONE -> {
	                    state = State.WAITING_OPCODE;
	                }
	        		case ERROR -> {
	                    closed = true;
	                    return;
	                }
					default -> throw new IllegalArgumentException("Unexpected value: " + state);
	        		
        		}
        	}
        }

        /**
         * Add a message to the message queue, tries to fill bufferOut and updateInterestOps
         *
         * @param bb
         */
        private void queueMessage(Message msg) {
        	queue.offer(msg);
            processOut();
            updateInterestOps();
        }

        /**
         * Try to fill bufferOut from the message queue
         *
         */
        private void processOut() {
        	while (bufferOut.hasRemaining() && !queue.isEmpty()) {
                Message msg = queue.peek();
                
                ByteBuffer messageBuffer = msg.toByteBuffer();
                
                if (bufferOut.remaining() >= messageBuffer.remaining()) {
                    bufferOut.put(messageBuffer);
                    queue.poll();
                } else {
                    break;
                }
            }
        }

        /**
         * Update the interestOps of the key looking only at values of the boolean
         * closed and of both ByteBuffers.
         *
         * The convention is that both buffers are in write-mode before the call to
         * updateInterestOps and after the call. Also it is assumed that process has
         * been be called just before updateInterestOps.
         */

        private void updateInterestOps() {
        	int ops = 0;
            if (bufferOut.hasRemaining()) {
                ops |= SelectionKey.OP_WRITE;
            }
            if (bufferIn.hasRemaining()) {
                ops |= SelectionKey.OP_READ;
            }
            if (ops == 0) {
                ops = SelectionKey.OP_READ;
            }
            key.interestOps(ops);
        }

        private void silentlyClose() {
            try {
                sc.close();
            } catch (IOException e) {
                // ignore exception
            }
        }

        /**
         * Performs the read action on sc
         *
         * The convention is that both buffers are in write-mode before the call to
         * doRead and after the call
         *
         * @throws IOException
         */
        private void doRead() throws IOException {
        	int bytesRead = sc.read(bufferIn);
            if (bytesRead == -1) {
                logger.info("Connection closed by server");
                closed = true;
                return;
            }
            if (bytesRead > 0) {
                processIn();
            }
            updateInterestOps();
        }

        /**
         * Performs the write action on sc
         *
         * The convention is that both buffers are in write-mode before the call to
         * doWrite and after the call
         *
         * @throws IOException
         */

        private void doWrite() throws IOException {
        	bufferOut.flip();
            int bytesWritten = sc.write(bufferOut);
            bufferOut.compact();
            
            if (bytesWritten > 0) {
                processOut();
            }
            updateInterestOps();
        }

        public void doConnect() throws IOException {
        	if (!sc.finishConnect()) {
                return;
            }
            
            logger.info("Connected to server ");
            
            updateInterestOps();
        }
    }

    private static int BUFFER_SIZE = 10_000;
    private static Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());

    private final SocketChannel sc;
    private final Selector selector;
    private final InetSocketAddress serverAddress;
    private final String login;
    private final Thread console;
    private Context uniqueContext;
    
    private final BlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);

    public ChatVaBienClient(String login, InetSocketAddress serverAddress) throws IOException {
        this.serverAddress = serverAddress;
        this.login = login;
        this.sc = SocketChannel.open();
        this.selector = Selector.open();
        this.console = Thread.ofPlatform().unstarted(this::consoleRun);
    }

    private void consoleRun() {
        try {
            try (var scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    var msg = scanner.nextLine();
                    sendCommand(msg);
                }
            }
            logger.info("Console thread stopping");
        } catch (InterruptedException e) {
            logger.info("Console thread has been interrupted");
        }
    }

    /**
     * Send instructions to the selector via a BlockingQueue and wake it up
     *
     * @param msg
     * @throws InterruptedException
     */

    private void sendCommand(String msg) throws InterruptedException {
        commandQueue.put(msg);
        selector.wakeup();
    }

    /**
     * Processes the command from the BlockingQueue 
     */

    private void processCommands() {
    	String command;
        while ((command = commandQueue.poll()) != null) {
            Message message = new Message(login, command);
            uniqueContext.queueMessage(message);
        }
    }

    public void launch() throws IOException {
        sc.configureBlocking(false);
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        uniqueContext = new Context(key);
        key.attach(uniqueContext);
        sc.connect(serverAddress);

        console.start();

        while (!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                processCommands();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isConnectable()) {
                uniqueContext.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                uniqueContext.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                uniqueContext.doRead();
            }
        } catch (IOException ioe) {
            // lambda call in select requires to tunnel IOException
            throw new UncheckedIOException(ioe);
        }
    }

    private void silentlyClose(SelectionKey key) {
        Channel sc = (Channel) key.channel();
        try {
            sc.close();
        } catch (IOException e) {
            // ignore exception
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 3) {
            usage();
            return;
        }
        new ChatVaBienClient(args[0], new InetSocketAddress(args[1], Integer.parseInt(args[2]))).launch();
    }

    private static void usage() {
        System.out.println("Usage : ClientChat login hostname port");
    }
}
