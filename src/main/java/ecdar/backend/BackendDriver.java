package ecdar.backend;

import EcdarProtoBuf.ComponentProtos;
import EcdarProtoBuf.EcdarBackendGrpc;
import EcdarProtoBuf.QueryProtos;
import com.google.protobuf.Empty;
import ecdar.Ecdar;
import ecdar.abstractions.Component;
import ecdar.abstractions.QueryState;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import javafx.util.Pair;
import org.springframework.util.SocketUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BackendDriver {
    private final Pair<AtomicInteger, AtomicInteger> numberOfReveaalConnections = new Pair<>(new AtomicInteger(0), new AtomicInteger(5));
    private final List<BackendConnection> reveaalConnections = new CopyOnWriteArrayList<>();

//    private final Pair<AtomicInteger, AtomicInteger> numberOfJEcdarConnections = new Pair<>(new AtomicInteger(0), new AtomicInteger(5));
//    private final List<BackendConnection> jEcdarConnections = new CopyOnWriteArrayList<>();

    private final String hostAddress;

    private final int deadlineForResponses = 20000;
    private final int rerunQueryDelay = 200;

    public BackendDriver(String hostAddress) {
        this.hostAddress = hostAddress;
    }

    public void addQueryToExecutionQueue(String query, BackendHelper.BackendNames backend, Consumer<Boolean> success, Consumer<BackendException> failure, QueryListener queryListener) {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                new ExecutableQuery(query, backend, success, failure, queryListener).execute();
            }
        }, rerunQueryDelay);
    }

    public Pair<ArrayList<String>, ArrayList<String>> getInputOutputs(String query) {
        // Pair is used as a tuple, not a key-value pair
        Pair<ArrayList<String>, ArrayList<String>> inputOutputs = new Pair<>(new ArrayList<>(), new ArrayList<>());
        ProcessBuilder pb = new ProcessBuilder("src/Reveaal", "-p", this.hostAddress + ":" + SocketUtils.findAvailableTcpPort());
        try {
            Process reveaalEngineInstance = pb.start();

//                                @Override
//                                public void onNext(QueryProtos.QueryResponse value) {
//                                    if (value.getError().endsWith("extra inputs")){
//                                        Matcher m = Pattern.compile("[\"]([^\"]+)[\"]").matcher(value.getError());
//                                        while (m.find()) {
//                                            inputOutputs.getKey().add(m.group(1));
//                                        }
//                                    } else if (value.getError().startsWith("extra outputs")) {
//                                        Matcher m = Pattern.compile("[\"]([^\"]+)[\"]").matcher(value.getError());
//                                        while (m.find()) {
//                                            inputOutputs.getValue().add(m.group(1));
//                                        }
//                                    }
//                                }
        } catch (IOException e) {
            e.printStackTrace();
        }

        inputOutputs.getKey().add("input");
        inputOutputs.getValue().add("output");

        return inputOutputs;
    }

    public void closeAllSockets() throws IOException {
        for (BackendConnection s : reveaalConnections) s.close();
        // for (BackendConnection s : jEcdarConnections) s.close();
    }

    public void setMaxNumberOfSockets(int i) {
        numberOfReveaalConnections.getValue().set(i);
        // numberOfJEcdarConnections.getValue().set(i);

        // ToDo NIELS: Potentially close connections until within new range [0, i]
    }

    public int getMaxNumberOfSockets() {
        return numberOfReveaalConnections.getValue().get();
    }

    private void executeQuery(ExecutableQuery executableQuery) {
        if (executableQuery.queryListener.getQuery().getQueryState() == QueryState.UNKNOWN) return;

        if (executableQuery.backend.equals(BackendHelper.BackendNames.jEcdar)) {
            jEcdarThread jECDARBackendThread = new jEcdarThread(executableQuery.query, executableQuery.success, executableQuery.failure, executableQuery.queryListener);
            jECDARBackendThread.start();
            return;
        }

        // Get available connection or start new
        final Optional<BackendConnection> connection;
//        if (executableQuery.backend.equals(BackendHelper.BackendNames.jEcdar)) {
//            connection = jEcdarConnections.stream().filter((element) -> !element.isRunningQuery()).findFirst();
//        } else {
        connection = reveaalConnections.stream().filter((element) -> !element.isRunningQuery()).findFirst();
        //}

        final BackendConnection backendConnection = connection.orElseGet(() ->
                (startNewBackendConnection(BackendHelper.BackendNames.Reveaal, numberOfReveaalConnections, reveaalConnections)));

        // : startNewBackendConnection(BackendHelper.BackendNames.jEcdar, numberOfJEcdarConnections, jEcdarConnections)

        // If the query connection is null, there are no available sockets
        // and the maximum number of sockets has already been reached
        if (backendConnection == null) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    executableQuery.execute();
                }
            }, rerunQueryDelay);
            return;
        }

        backendConnection.setExecutableQuery(executableQuery);

        QueryProtos.ComponentsUpdateRequest.Builder componentsBuilder = QueryProtos.ComponentsUpdateRequest.newBuilder();
        for (Component c : Ecdar.getProject().getComponents()) {
            componentsBuilder.addComponents(ComponentProtos.Component.newBuilder().setJson(c.serialize().toString()).build());
        }

        StreamObserver<Empty> observer = new StreamObserver<>() {
            private boolean error = false;

            @Override
            public void onNext(Empty value) {
            }

            @Override
            public void onError(Throwable t) {
                if (executableQuery.queryListener.getQuery().getQueryState() == QueryState.UNKNOWN) {
                    backendConnection.setExecutableQuery(null);
                } else {
                    handleBackendError(t, backendConnection);
                    error = true;
                }
            }

            @Override
            public void onCompleted() {
                if (!error) {
                    StreamObserver<EcdarProtoBuf.QueryProtos.QueryResponse> responseObserver = new StreamObserver<>() {
                        @Override
                        public void onNext(QueryProtos.QueryResponse value) {
                            if (executableQuery.queryListener.getQuery().getQueryState() == QueryState.UNKNOWN) {
                                backendConnection.setExecutableQuery(null);
                                return;
                            }
                            handleResponse(backendConnection.getExecutableQuery(), value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            handleBackendError(t, backendConnection);
                        }

                        @Override
                        public void onCompleted() {
                            backendConnection.setExecutableQuery(null);
                        }
                    };
                    backendConnection.getStub().withDeadlineAfter(deadlineForResponses, TimeUnit.MILLISECONDS).sendQuery(QueryProtos.Query.newBuilder().setId(0).setQuery(backendConnection.getExecutableQuery().query).build(), responseObserver);
                }
            }
        };

        backendConnection.getStub().withDeadlineAfter(deadlineForResponses, TimeUnit.MILLISECONDS).updateComponents(componentsBuilder.build(), observer);
    }

    private void handleBackendError(Throwable t, BackendConnection backendConnection) {
        // Each error starts with a capitalized description of the error equal to the gRPC error type encountered
        String errorType = t.getMessage().split(":\\s+", 2)[0];
        final ExecutableQuery query = backendConnection.getExecutableQuery();

        switch (errorType) {
            case "CANCELLED":
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("The query was cancelled"));
                break;
            case "DEADLINE_EXCEEDED":
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("The backend did not answer the request in time"));

                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        query.execute();
                    }
                }, rerunQueryDelay);

                break;
            case "UNIMPLEMENTED":
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.SYNTAX_ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("The query type is not supported by the backend"));
                break;
            case "INTERNAL":
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("Reveaal was unable to execute this query:\n" + t.getMessage().split(": ", 2)[1]));

                /*new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        query.execute();
                    }
                }, rerunQueryDelay);*/

                break;
            case "UNKNOWN":
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("The backend encountered an unknown error"));
                break;
            default:
                backendConnection.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                backendConnection.getExecutableQuery().failure.accept(new BackendException.QueryErrorException("The query failed and gave the following error: " + errorType));

                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        query.execute();
                    }
                }, rerunQueryDelay);

                try {
                    backendConnection.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                break;
        }

        backendConnection.setExecutableQuery(null);
    }

    private void handleResponse(ExecutableQuery executableQuery, QueryProtos.QueryResponse value) {
        if (value.hasRefinement() && value.getRefinement().getSuccess()) {
            executableQuery.queryListener.getQuery().setQueryState(QueryState.SUCCESSFUL);
            executableQuery.success.accept(true);
        } else if (value.hasConsistency() && value.getConsistency().getSuccess()) {
            System.out.println("Consistency");
            executableQuery.queryListener.getQuery().setQueryState(QueryState.SUCCESSFUL);
            executableQuery.success.accept(true);
        } else if (value.hasDeterminism() && value.getDeterminism().getSuccess()) {
            System.out.println("Determinism");
            executableQuery.queryListener.getQuery().setQueryState(QueryState.SUCCESSFUL);
            executableQuery.success.accept(true);
        } else if (value.hasComponent()) {
            System.out.println("Component");
            executableQuery.queryListener.getQuery().setQueryState(QueryState.SUCCESSFUL);
            executableQuery.success.accept(true);
        } else {
            System.out.println(value.getError());
            executableQuery.queryListener.getQuery().setQueryState(QueryState.ERROR);
            executableQuery.success.accept(false);
        }
    }

    private BackendConnection startNewBackendConnection(BackendHelper.BackendNames backend, Pair<AtomicInteger, AtomicInteger> numberOfSockets, List<BackendConnection> backendConnections) {
        if (numberOfSockets.getKey().get() < numberOfSockets.getValue().get()) {
            try {
                Process p;
                int portNumber = SocketUtils.findAvailableTcpPort();

                do {
                    ProcessBuilder pb;
                    if (backend.equals(BackendHelper.BackendNames.jEcdar)) {
                        pb = new ProcessBuilder("java", "-jar", "src/libs/j-Ecdar.jar");
                    } else {
                        pb = new ProcessBuilder("src/Reveaal", "-p", this.hostAddress + ":" + portNumber);
                    }

                    p = pb.start();
                    // If the process is not alive, it failed while starting up, try again
                } while (!p.isAlive());

                ManagedChannel channel = ManagedChannelBuilder.forTarget(this.hostAddress + ":" + portNumber)
                        .usePlaintext()
                        .idleTimeout(2000, TimeUnit.MILLISECONDS)
                        .keepAliveTime(1000, TimeUnit.MILLISECONDS)
                        .keepAliveTimeout(2000, TimeUnit.MILLISECONDS)
                        .build();

                EcdarBackendGrpc.EcdarBackendStub stub = EcdarBackendGrpc.newStub(channel);
                BackendConnection newConnection = new BackendConnection(p, stub);
                backendConnections.add(newConnection);
                numberOfSockets.getKey().getAndIncrement();

                return newConnection;
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Max number of sockets already reached");
        }

        return null;
    }

    private class ExecutableQuery {
        private final String query;
        private final BackendHelper.BackendNames backend;
        private final Consumer<Boolean> success;
        private final Consumer<BackendException> failure;
        private final QueryListener queryListener;

        ExecutableQuery(String query, BackendHelper.BackendNames backend, Consumer<Boolean> success, Consumer<BackendException> failure, QueryListener queryListener) {
            this.query = query;
            this.backend = backend;
            this.success = success;
            this.failure = failure;
            this.queryListener = queryListener;
        }

        public void execute() {
            executeQuery(this);
        }
    }

    private class BackendConnection {
        private final Process process;
        private final EcdarBackendGrpc.EcdarBackendStub stub;
        private ExecutableQuery executableQuery = null;

        BackendConnection(Process process, EcdarBackendGrpc.EcdarBackendStub stub) throws IOException {
            this.process = process;
            this.stub = stub;
        }

        public EcdarBackendGrpc.EcdarBackendStub getStub() {
            return stub;
        }

        public ExecutableQuery getExecutableQuery() {
            return executableQuery;
        }

        public void setExecutableQuery(ExecutableQuery executableQuery) {
            this.executableQuery = executableQuery;
        }

        public boolean isRunningQuery() {
            return executableQuery != null;
        }

        public void close() throws IOException {
            // Remove the socket from the socket list
//            if (jEcdarConnections.remove(this)) {
//                numberOfJEcdarConnections.getKey().getAndDecrement();
            /*} else */
            if (reveaalConnections.remove(this)) {
                numberOfReveaalConnections.getKey().getAndDecrement();
            }

            process.destroy();
        }
    }

    enum TraceType {
        NONE, SOME, SHORTEST, FASTEST;

        @Override
        public String toString() {
            return "trace " + this.ordinal();
        }
    }
}
