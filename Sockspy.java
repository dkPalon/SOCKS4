

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sockspy {
    // constants
    private static final int MAX_REQUEST_BYTES_NUMBER = 50;
    private static final int MINIMUM_REQUEST_LENGTH = 8;
    private static final int REQUEST_OK = 90;
    private static final int REQUEST_REJECTED_OR_FAILED = 91;
    private static final int PORT = 8080;
    private static final int MAX_PARALLEL_CONNECTIONS = 20;
    private static final int PROTOCOL_VERSION = 4;
    private static final int CONNECT_REQUEST = 1;
    private static final int TIMEOUT_TIME = 2000;
    private static final int BUF_SIZE = 4096;

    // error message definitions
    private static final String SUCCESSFUL_CONNECTION = "Successful connection from ";
    private static final String PASSWORD_FOUND = "Password found! ";
    private static final String CLOSING_CONNECTION = "Closing connection from: ";
    private static final String CONNECTER_ERROR = "Unknown Connection Error";
    private static final String UNSUPPOERTED_SOCKS_PROT = "Unsupported SOCKS protocol version ";
    private static final String UNKNOWN_SOCKS_COMMAND_ERROR = "Unknown socks command! currently supports only CONNECT request";
    private static final String ILLEGAL_IP_LENGTH = "IP address is of illegal length";
    private static final String ILLEGAL_REQUEST_LENGTH_ERROR = "The request has an illegal length - under 8 characters";
    private static final String ILLEGAL_PORT_NUMBER_ERROR = "Illegal port number. given port number is 0";
    private static final String CONNECTION_TO_DESTINATION_IP_FAILED = "Connection error: while connecting to destination: connect timed out";
    private static final String TIMOUT_ERROR = "Timeout limit reached closing all connection";
    private static final String CLIENTHOSTIOERROR = "Error during client host communication";
    private static final String NOCONNECT = "No connect message received";


    /**
     * Main Server Function that listens to port 8080
     * @param args - not used
     */
    public static void main (String[]args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)){
            // creating a thread pool to service a maximum of 20 connections
            ThreadPoolExecutor connections = new ThreadPoolExecutor(0, MAX_PARALLEL_CONNECTIONS,
                    1, TimeUnit.HOURS, new ArrayBlockingQueue(1), new RejectedConnection());
            // server waiting for connection
            while (true) {
                try {
                    connections.submit(new Handler(serverSocket.accept()));
                }
                catch (Exception e) {
                    System.err.println("Error during the connection process to the client");
                }
            }

        }
        catch (Exception e) {
            System.err.println("Proxy server Crashed");
        }
    }

    /*-------Rejected Connection Handling class-------*/
    /**
     * This class handles a case of a rejected connection by the SOCKS4 server
     */
    private static class RejectedConnection implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            executor.remove(r);
            System.err.println("Server Capacity is full, a connection request has been declined");
        }

    }


    /*------------Connection handling class------------*/

    /**
     * The Handler class that handles the actual connections
     */
    private static class Handler extends Thread{

        private Socket clientSocket;
        private int destinationPort;
        private InetAddress destinationIP;

        // creating a new handler instance which is assigned to the given socket
        Handler(Socket clientSocket) {
            this.clientSocket = clientSocket;

        }

        /**
         * The Actual SOCKS4 implementation
         */
        @Override
        public void run() {
            try (DataInputStream clientInput = new DataInputStream (clientSocket.getInputStream());
                 DataOutputStream clientOutput = new DataOutputStream(clientSocket.getOutputStream())) {
                // setting timeout for client
                clientSocket.setSoTimeout(TIMEOUT_TIME);

                // reading request from client
                byte[] byteArray = new byte[MAX_REQUEST_BYTES_NUMBER];
                int successFlag = clientInput.read(byteArray);
                if(successFlag == -1) {
                    System.err.println("end of stream has been reached");
                    throw new NullPointerException();
                }

                // processing connect request and building the response
                byte[] response = processRequest(byteArray);

                // bad request results in rejection of connection
                if (response[1] == REQUEST_REJECTED_OR_FAILED) {
                    clientOutput.write(response);
                    clientOutput.flush();
                    closingClientConnectionMessage(clientSocket);
                }
                // request is good, attempting to connect to host
                else if (response[1] == REQUEST_OK) {
                    // attempting to connect to host
                    try (Socket hostSocket = new Socket(destinationIP, destinationPort);
                         BufferedWriter HostOutput = new BufferedWriter (new OutputStreamWriter(hostSocket.getOutputStream()))) {
                        hostSocket.setSoTimeout(TIMEOUT_TIME);

                        clientOutput.write(response);
                        clientOutput.flush();

                        // connection successful
                        successfulConnectionMessage(clientSocket);
                        try (BufferedReader clientBufReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                            // creating thread pool for 2 directional simultaneous communication between client and host
                            ExecutorService ioService = Executors.newFixedThreadPool(2);
                            // for HTTP request we extract the password from the GET request
                            if (destinationPort == 80) {
                                // reading from client and routing to host
                                processClientDataHTTP(clientBufReader, HostOutput);
                            }

                            // Other traffic
                            while (true) {
                                ioService.submit(new processData(clientSocket.getOutputStream(), hostSocket.getInputStream()));
                                ioService.submit(new processData(hostSocket.getOutputStream(), clientSocket.getInputStream()));
                                // if threads did not terminate in allocated amount of time a read BLOCK must have occurred
                                if (!ioService.awaitTermination(TIMEOUT_TIME, TimeUnit.MILLISECONDS)) {
                                    throw new SocketTimeoutException();
                                }
                            }
                        }
                        // timeout, closing connection
                        catch (SocketTimeoutException e) {
                            closingConnectionMessage(clientSocket);
                        }
                        // client host communication error
                        catch (IOException e) {
                            System.err.println(CLIENTHOSTIOERROR);
                        }
                    }
                    // failed to connect to host, rejecting client request
                    catch (Exception e) {
                        response[1] = REQUEST_REJECTED_OR_FAILED;
                        clientOutput.write(response);
                        clientOutput.flush();
                        System.err.println(CONNECTION_TO_DESTINATION_IP_FAILED);
                        closingClientConnectionMessage(clientSocket);
                    }
                }

            }
            // no connect message received
            catch (NullPointerException e) {
                System.err.println(NOCONNECT);
            }
            // client timed out
            catch (SocketTimeoutException e) {
                System.err.println(TIMOUT_ERROR);
            }
            // unknown connection error
            catch (IOException e) {
                System.err.println(CONNECTER_ERROR);
            }
            finally {
                try {
                    clientSocket.close();
                }
                catch (IOException e) {
                    System.err.println("Error during closure Process");
                }
            }
        }

        /*------------Non HTTP traffic class------------*/

        /**
         * This class is responsible for client - host communication
         */
        private class processData implements Runnable {

            OutputStream out;
            InputStream in;

            private processData(OutputStream out, InputStream in) {
                this.in = in;
                this.out = out;
            }

            @Override
            public void run() {
                try {
                    byte[] buf = new byte[BUF_SIZE];
                    int numOfbytes = 0;
                    while ((numOfbytes = in.read(buf)) > 0 && numOfbytes < BUF_SIZE) {
                        out.write(buf, 0, numOfbytes);
                    }
                }
                catch (Exception e) {
                    return;
                }

            }
        }

        /*------------HTTP traffic method------------*/

        /**
         * This function pareses a HTTP GET request and extracts the password
         * @param clientBufReader - the client
         * @param HostOutput - the host
         * @throws Exception - Some IO error
         */
        private void processClientDataHTTP(BufferedReader clientBufReader, BufferedWriter HostOutput) throws Exception {
            String line = "";
            StringBuilder clientData = new StringBuilder();
            line = clientBufReader.readLine();

            // regex definition
            Pattern authorizationPattern = Pattern.compile("^[Aa]uthorization: [Bb]asic (.*)");
            String credentials = "";
            Pattern hostPattern = Pattern.compile("Host: (.*)");
            String hostName = "";
            Base64.Decoder decoder = Base64.getDecoder();

            // reading client data
            while (clientBufReader.ready()) {

                // password/username regex
                Matcher authorizationMatcher = authorizationPattern.matcher(line);
                if (authorizationMatcher.matches()) {
                    credentials = authorizationMatcher.group(1);
                    credentials = new String(decoder.decode(credentials.getBytes("UTF-8")));
                }

                // hostname regex
                Matcher hostMatcher = hostPattern.matcher(line);
                if (hostMatcher.matches()) {
                    hostName = hostMatcher.group(1);
                }

                // parsing request
                clientData.append(line);
                clientData.append("\r\n");

                line = clientBufReader.readLine();
            }

            // printing credentials
            System.out.println(PASSWORD_FOUND + "http//:" + credentials + "@" + hostName);

            // parsing request
            clientData.append("\r\n\r\n");

            // writing request to host
            HostOutput.write(clientData.toString());
            HostOutput.flush();
        }

        /*------------UI messages methods------------*/
        private void successfulConnectionMessage(Socket clientSocket){
            System.out.println(SUCCESSFUL_CONNECTION + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort()  + " to "  + destinationIP.getHostAddress() + ":" + destinationPort);
        }

        private void closingConnectionMessage(Socket clientSocket){
            System.out.println(CLOSING_CONNECTION + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort()  + " to "  + destinationIP.getHostAddress() + ":" + destinationPort);
        }

        private void closingClientConnectionMessage(Socket clientSocket){
            System.out.println(CLOSING_CONNECTION + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());
        }

        /*---------------Connect message processing methods------------------*/
        /**
         * This method process the client's request and checks its validity
         * @param request - the client request
         * @return response - the SOCKS4 server response
         */
        private byte[] processRequest(byte[] request){
            byte[] response = new byte[8];
            byte replyStatus = REQUEST_OK;

            // check if request is valid
            if (!isRequestLengthLegal(request, response) ||
                    !processProtocolVersionRequest(request[0]) ||
                    !processSOCKScommandCode(request[1]) ||
                    !processDestinationPortNumber(request[2], request[3]) ||
                    !processDestinationIP(request)) {
                System.out.println("request rejected!");
                replyStatus = REQUEST_REJECTED_OR_FAILED;
            }

            response[1] = replyStatus;
            return response;
        }

        // VN - SOCKS protocol version number
        private boolean processProtocolVersionRequest(byte version){
            if(version != PROTOCOL_VERSION){
                System.err.println(UNSUPPOERTED_SOCKS_PROT  + "(got " + version + ")");
                return false;
            }
            return true;
        }

        // DSPORT
        private boolean processDestinationPortNumber(byte firstByte, byte secondByte){
            this.destinationPort = (firstByte & 0xff) << 8 | (secondByte & 0xff);
            if(this.destinationPort <= 0){
                System.err.println(ILLEGAL_PORT_NUMBER_ERROR);
                return false;
            }
            return true;
        }

        // CD - SOCKS command code and should be 1 for CONNECT request
        private boolean processSOCKScommandCode(byte code){
            if(code != CONNECT_REQUEST){
                System.err.println(UNKNOWN_SOCKS_COMMAND_ERROR);
                return false;
            }
            return true;
        }

        // DSTIP
        private boolean processDestinationIP(byte[] requestArray){
            try{
                // SOCKS 4A
                if (requestArray[4] == 0 && requestArray[5] == 0 && requestArray[6] == 0 && requestArray[7] != 0) {
                    int start = 0;
                    int end = 0;
                    int i = 8;
                    while (requestArray[i] != 0) {
                        i++;
                    }
                    start = i + 1;
                    i++;
                    while (requestArray[i] != 0) {
                        i++;
                    }
                    end = i;
                    destinationIP = InetAddress.getByName(new String(Arrays.copyOfRange(requestArray, start, end)));
                }
                // regular SOCKS
                else {
                    destinationIP = InetAddress.getByAddress(Arrays.copyOfRange(requestArray, 4, 8));
                }
            }
            catch (UnknownHostException e){
                System.err.println(ILLEGAL_IP_LENGTH);
                return false;
            }
            return true;
        }

        // request length must be minimum of length 8
        private boolean isRequestLengthLegal(byte[] request, byte[] response){
            if(request.length < MINIMUM_REQUEST_LENGTH){
                System.err.println(ILLEGAL_REQUEST_LENGTH_ERROR);
                return false;
            }
            // dest port
            response[2] = request[2];
            response[3] = request[3];

            // ip
            response[4] = request[4];
            response[5] = request[5];
            response[6] = request[6];
            response[7] = request[7];
            return true;
        }
    }




}